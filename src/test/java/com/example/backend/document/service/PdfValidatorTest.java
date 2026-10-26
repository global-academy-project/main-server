package com.example.backend.document.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.example.backend.support.TestFixtures.expectCode;
import static com.example.backend.support.TestFixtures.pdf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PdfValidator: 업로드 파일 검증")
class PdfValidatorTest {

    private final PdfValidator validator = new PdfValidator();

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/pdf", bytes);
    }

    /** 실제로 큰 파일을 만들지 않고 크기만 속이기 위한 모의 파일 */
    private static MultipartFile fakeSized(long size, String name, byte[] content) throws IOException {
        MultipartFile f = mock(MultipartFile.class);
        when(f.isEmpty()).thenReturn(false);
        when(f.getSize()).thenReturn(size);
        when(f.getOriginalFilename()).thenReturn(name);
        when(f.getBytes()).thenReturn(content);
        return f;
    }

    @Test
    @DisplayName("정상 PDF 는 페이지 수를 돌려준다")
    void valid_returnsPageCount() {
        assertThat(validator.validate(file("a.pdf", pdf(3)))).isEqualTo(3);
    }

    @Test
    @DisplayName("경계: 정확히 50페이지는 허용")
    void pages_atLimit_allowed() {
        assertThat(validator.validate(file("a.pdf", pdf(PdfValidator.MAX_PAGES)))).isEqualTo(50);
    }

    @Test
    @DisplayName("경계: 51페이지는 INVALID_REQUEST, 메시지에 최대 페이지 수 포함")
    void pages_overLimit_rejected() {
        BusinessException e = expectCode(() -> validator.validate(file("a.pdf", pdf(PdfValidator.MAX_PAGES + 1))),
                ErrorCode.INVALID_REQUEST);
        assertThat(e.getMessage()).contains("50");
    }

    @Test
    @DisplayName("페이지가 0장인 PDF 는 INVALID_REQUEST")
    void zeroPages_rejected() {
        BusinessException e = expectCode(() -> validator.validate(file("a.pdf", pdf(0))), ErrorCode.INVALID_REQUEST);
        assertThat(e.getMessage()).contains("페이지가 없는");
    }

    @Test
    @DisplayName("파일이 null 이면 INVALID_REQUEST")
    void nullFile_rejected() {
        expectCode(() -> validator.validate(null), ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("0바이트 파일은 INVALID_REQUEST")
    void emptyFile_rejected() {
        expectCode(() -> validator.validate(file("a.pdf", new byte[0])), ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("경계: 20MB 초과(1바이트)는 FILE_TOO_LARGE")
    void size_overLimit_rejected() throws IOException {
        expectCode(() -> validator.validate(fakeSized(PdfValidator.MAX_SIZE + 1, "a.pdf", pdf(1))),
                ErrorCode.FILE_TOO_LARGE);
    }

    @Test
    @DisplayName("경계: 정확히 20MB 는 크기 검사를 통과")
    void size_atLimit_allowed() throws IOException {
        assertThat(validator.validate(fakeSized(PdfValidator.MAX_SIZE, "a.pdf", pdf(1)))).isEqualTo(1);
    }

    @Test
    @DisplayName("크기 검사가 확장자 검사보다 먼저 일어난다")
    void size_checkedBeforeExtension() throws IOException {
        expectCode(() -> validator.validate(fakeSized(PdfValidator.MAX_SIZE + 1, "a.txt", new byte[]{1})),
                ErrorCode.FILE_TOO_LARGE);
    }

    @Test
    @DisplayName("확장자가 pdf 가 아니면 UNSUPPORTED_FILE_TYPE")
    void wrongExtension_rejected() {
        expectCode(() -> validator.validate(file("a.txt", pdf(1))), ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("이중 확장자(report.pdf.exe)는 거부")
    void doubleExtension_rejected() {
        expectCode(() -> validator.validate(file("report.pdf.exe", pdf(1))), ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("파일명이 없거나 비어 있으면 UNSUPPORTED_FILE_TYPE")
    void noFileName_rejected() {
        expectCode(() -> validator.validate(file(null, pdf(1))), ErrorCode.UNSUPPORTED_FILE_TYPE);
        expectCode(() -> validator.validate(file("pdf", pdf(1))), ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("확장자는 대소문자를 구분하지 않는다 (.PDF 허용)")
    void extension_caseInsensitive() {
        assertThat(validator.validate(file("SCAN.PDF", pdf(2)))).isEqualTo(2);
    }

    @Test
    @DisplayName("확장자만 .pdf 이고 내용이 PDF 가 아니면 거부 (매직 넘버 검사)")
    void fakePdf_rejected() {
        byte[] text = "hello world, not a pdf".getBytes(StandardCharsets.UTF_8);
        expectCode(() -> validator.validate(file("a.pdf", text)), ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("5바이트 미만의 짧은 파일은 거부")
    void tooShort_rejected() {
        expectCode(() -> validator.validate(file("a.pdf", "%PDF".getBytes(StandardCharsets.US_ASCII))),
                ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("헤더만 PDF 인 손상 파일은 '열 수 없는 PDF' 로 거부")
    void corruptPdf_rejected() {
        byte[] corrupt = "%PDF-1.7\nthis is garbage".getBytes(StandardCharsets.US_ASCII);
        BusinessException e = expectCode(() -> validator.validate(file("a.pdf", corrupt)),
                ErrorCode.UNSUPPORTED_FILE_TYPE);
        assertThat(e.getMessage()).contains("열 수 없는");
    }

    @Test
    @DisplayName("파일을 읽다 IOException 이 나면 INVALID_REQUEST")
    void unreadable_rejected() throws IOException {
        MultipartFile f = mock(MultipartFile.class);
        when(f.isEmpty()).thenReturn(false);
        when(f.getSize()).thenReturn(10L);
        when(f.getOriginalFilename()).thenReturn("a.pdf");
        when(f.getBytes()).thenThrow(new IOException("disk error"));
        BusinessException e = expectCode(() -> validator.validate(f), ErrorCode.INVALID_REQUEST);
        assertThat(e.getMessage()).contains("읽을 수 없습니다");
    }
}
