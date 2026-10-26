package com.example.backend.ocr.service;

import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.type.DocumentStatus;
import com.example.backend.common.type.Engine;
import com.example.backend.common.type.PageSource;
import com.example.backend.document.dto.StoredFile;
import com.example.backend.document.entity.Document;
import com.example.backend.document.repository.DocumentRepository;
import com.example.backend.document.storage.LocalFileStorage;
import com.example.backend.ocr.dto.ExportFile;
import com.example.backend.ocr.dto.RunDetail;
import com.example.backend.ocr.dto.RunExport;
import com.example.backend.ocr.entity.OcrImage;
import com.example.backend.ocr.entity.OcrPage;
import com.example.backend.ocr.entity.OcrRun;
import com.example.backend.ocr.repository.OcrImageRepository;
import com.example.backend.ocr.repository.OcrPageRepository;
import com.example.backend.ocr.repository.OcrRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.example.backend.support.TestFixtures.document;
import static com.example.backend.support.TestFixtures.expectCode;
import static com.example.backend.support.TestFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("OcrRunService: 실행 조회 / 내보내기 / 추출 이미지")
class OcrRunServiceTest {

    private static final Long USER = 1L;

    private DocumentRepository documentRepository;
    private OcrRunRepository runRepository;
    private OcrPageRepository pageRepository;
    private OcrImageRepository imageRepository;
    private LocalFileStorage storage;
    private OcrRunService service;

    private Document document;
    private OcrRun doneRun;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        runRepository = mock(OcrRunRepository.class);
        pageRepository = mock(OcrPageRepository.class);
        imageRepository = mock(OcrImageRepository.class);
        storage = mock(LocalFileStorage.class);
        service = new OcrRunService(documentRepository, runRepository, pageRepository, imageRepository, storage);

        document = document(10L, USER, DocumentStatus.DONE);
        doneRun = run(20L, 10L, Engine.TESSERACT);
        doneRun.complete(500, new BigDecimal("0.9000"));
        when(documentRepository.findByIdAndUserId(10L, USER)).thenReturn(Optional.of(document));
        when(runRepository.findByIdAndDocumentId(20L, 10L)).thenReturn(Optional.of(doneRun));
    }

    private static OcrPage page(int no, String text) {
        return new OcrPage(20L, no, PageSource.OCR, text, new BigDecimal("0.9000"), 100);
    }

    @Test
    @DisplayName("txt 내보내기: 페이지 구분선 + 본문, 텍스트가 null 인 페이지는 빈 줄, 파일명은 {원본명}_{엔진}.txt")
    void export_txt() {
        when(pageRepository.findByRunIdOrderByPageNo(20L)).thenReturn(List.of(page(1, "첫 페이지"), page(2, null)));

        ExportFile f = service.export(USER, 10L, 20L, "txt");

        assertThat(f.fileName()).isEqualTo("보고서_TESSERACT.txt");
        assertThat(f.mediaType().toString()).contains("text/plain").containsIgnoringCase("utf-8");
        assertThat(f.body()).isEqualTo("===== Page 1 =====\n첫 페이지\n\n===== Page 2 =====\n\n\n");
    }

    @Test
    @DisplayName("json 내보내기: RunExport(images 제외) 와 application/json")
    void export_json() {
        when(pageRepository.findByRunIdOrderByPageNo(20L)).thenReturn(List.of(page(1, "a")));

        ExportFile f = service.export(USER, 10L, 20L, "json");

        assertThat(f.fileName()).isEqualTo("보고서_TESSERACT.json");
        assertThat(f.mediaType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(f.body()).isInstanceOf(RunExport.class);
        assertThat(((RunExport) f.body()).pages()).hasSize(1);
    }

    @Test
    @DisplayName("확장자가 없는 원본 파일명도 안전하게 처리")
    void export_fileNameWithoutExtension() {
        ReflectionTestUtils.setField(document, "originalName", "noext");
        when(pageRepository.findByRunIdOrderByPageNo(20L)).thenReturn(List.of());

        assertThat(service.export(USER, 10L, 20L, "txt").fileName()).isEqualTo("noext_TESSERACT.txt");
    }

    @Test
    @DisplayName("페이지가 없는 DONE run 의 txt 는 빈 문자열")
    void export_noPages_emptyText() {
        when(pageRepository.findByRunIdOrderByPageNo(20L)).thenReturn(List.of());

        assertThat(service.export(USER, 10L, 20L, "txt").body()).isEqualTo("");
    }

    @ParameterizedTest(name = "format=\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"csv", "TXT", "Json", "pdf"})
    @DisplayName("txt/json 이외(대문자 포함)의 format 은 INVALID_REQUEST")
    void export_invalidFormat(String format) {
        expectCode(() -> service.export(USER, 10L, 20L, format), ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("DONE 이 아닌 run(처리 중/실패)은 내보낼 수 없다")
    void export_notDone_rejected() {
        OcrRun processing = run(21L, 10L, Engine.AUTO);
        OcrRun failed = run(22L, 10L, Engine.AUTO);
        failed.fail("OCR_FAILED: x");
        when(runRepository.findByIdAndDocumentId(21L, 10L)).thenReturn(Optional.of(processing));
        when(runRepository.findByIdAndDocumentId(22L, 10L)).thenReturn(Optional.of(failed));

        expectCode(() -> service.export(USER, 10L, 21L, "txt"), ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.export(USER, 10L, 22L, "json"), ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("남의 문서는 DOCUMENT_NOT_FOUND, 다른 문서의 run 번호는 RUN_NOT_FOUND")
    void ownership() {
        when(documentRepository.findByIdAndUserId(10L, 99L)).thenReturn(Optional.empty());
        expectCode(() -> service.export(99L, 10L, 20L, "txt"), ErrorCode.DOCUMENT_NOT_FOUND);
        expectCode(() -> service.get(99L, 10L, 20L), ErrorCode.DOCUMENT_NOT_FOUND);
        expectCode(() -> service.list(99L, 10L), ErrorCode.DOCUMENT_NOT_FOUND);

        expectCode(() -> service.get(USER, 10L, 777L), ErrorCode.RUN_NOT_FOUND);
        expectCode(() -> service.export(USER, 10L, 777L, "txt"), ErrorCode.RUN_NOT_FOUND);
    }

    @Test
    @DisplayName("run 상세: 이미지 URL 은 /api/v1/documents/{문서}/runs/{run}/images/{id}")
    void get_buildsImageUrl() {
        OcrImage image = new OcrImage(20L, 2, "images/10/20/1.png", "png", 64, 48);
        ReflectionTestUtils.setField(image, "id", 5L);
        when(pageRepository.findByRunIdOrderByPageNo(20L)).thenReturn(List.of(page(1, "a")));
        when(imageRepository.findByRunIdOrderByPageNo(20L)).thenReturn(List.of(image));

        RunDetail detail = service.get(USER, 10L, 20L);

        assertThat(detail.images()).hasSize(1);
        assertThat(detail.images().get(0).url()).isEqualTo("/api/v1/documents/10/runs/20/images/5");
        assertThat(detail.images().get(0).pageNo()).isEqualTo(2);
    }

    @Test
    @DisplayName("추출 이미지: 없는 이미지 / 파일이 사라진 이미지는 IMAGE_NOT_FOUND")
    void getImage_notFound() {
        OcrImage image = new OcrImage(20L, 1, "images/10/20/1.png", "png", 1, 1);
        ReflectionTestUtils.setField(image, "id", 5L);
        when(imageRepository.findByIdAndRunId(404L, 20L)).thenReturn(Optional.empty());
        when(imageRepository.findByIdAndRunId(5L, 20L)).thenReturn(Optional.of(image));
        when(storage.exists("images/10/20/1.png")).thenReturn(false);

        expectCode(() -> service.getImage(USER, 10L, 20L, 404L), ErrorCode.IMAGE_NOT_FOUND);
        expectCode(() -> service.getImage(USER, 10L, 20L, 5L), ErrorCode.IMAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("추출 이미지: 정상이면 경로와 image-{id}.{형식} 이름 반환")
    void getImage_ok() {
        OcrImage image = new OcrImage(20L, 1, "images/10/20/1.png", "png", 1, 1);
        ReflectionTestUtils.setField(image, "id", 5L);
        when(imageRepository.findByIdAndRunId(5L, 20L)).thenReturn(Optional.of(image));
        when(storage.exists("images/10/20/1.png")).thenReturn(true);
        when(storage.resolve("images/10/20/1.png")).thenReturn(Path.of("/data/images/10/20/1.png"));

        StoredFile f = service.getImage(USER, 10L, 20L, 5L);

        assertThat(f.originalName()).isEqualTo("image-5.png");
        assertThat(f.path()).isEqualTo(Path.of("/data/images/10/20/1.png"));
    }

    @Test
    @DisplayName("다른 run 의 이미지 번호로 접근하면 이미지 조회 단계에서 차단(IMAGE_NOT_FOUND)")
    void getImage_wrongRun_blocked() {
        when(imageRepository.findByIdAndRunId(5L, 20L)).thenReturn(Optional.empty());

        expectCode(() -> service.getImage(USER, 10L, 20L, 5L), ErrorCode.IMAGE_NOT_FOUND);
        verifyNoInteractions(storage);
    }
}
