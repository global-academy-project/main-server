package com.example.backend.ocr.service;

import com.example.backend.common.type.DocumentStatus;
import com.example.backend.common.type.Engine;
import com.example.backend.common.type.NetworkMode;
import com.example.backend.common.type.PageSource;
import com.example.backend.common.type.RunStatus;
import com.example.backend.document.entity.Document;
import com.example.backend.document.repository.DocumentRepository;
import com.example.backend.document.storage.LocalFileStorage;
import com.example.backend.ocr.client.ImageResult;
import com.example.backend.ocr.client.OcrResult;
import com.example.backend.ocr.client.PageResult;
import com.example.backend.ocr.entity.OcrImage;
import com.example.backend.ocr.entity.OcrPage;
import com.example.backend.ocr.entity.OcrRun;
import com.example.backend.ocr.repository.OcrImageRepository;
import com.example.backend.ocr.repository.OcrPageRepository;
import com.example.backend.ocr.repository.OcrRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static com.example.backend.support.TestFixtures.document;
import static com.example.backend.support.TestFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OcrResultWriter: OCR 결과를 DB/저장소에 반영")
class OcrResultWriterTest {

    private OcrRunRepository runRepository;
    private DocumentRepository documentRepository;
    private OcrPageRepository pageRepository;
    private OcrImageRepository imageRepository;
    private LocalFileStorage storage;
    private OcrResultWriter writer;

    private OcrRun run;
    private Document document;

    @BeforeEach
    void setUp() {
        runRepository = mock(OcrRunRepository.class);
        documentRepository = mock(DocumentRepository.class);
        pageRepository = mock(OcrPageRepository.class);
        imageRepository = mock(OcrImageRepository.class);
        storage = mock(LocalFileStorage.class);
        writer = new OcrResultWriter(runRepository, documentRepository, pageRepository, imageRepository, storage);

        run = run(20L, 10L, Engine.AUTO);
        document = document(10L, 1L, DocumentStatus.PROCESSING);
        when(runRepository.findById(20L)).thenReturn(Optional.of(run));
        when(documentRepository.findById(10L)).thenReturn(Optional.of(document));
    }

    private static OcrResult result(List<PageResult> pages, List<ImageResult> images) {
        return new OcrResult(Engine.TESSERACT, NetworkMode.CLOSED, pages.size(), 1234,
                new BigDecimal("0.9100"), pages, images, "요약입니다");
    }

    @Test
    @DisplayName("성공: run 은 실제 엔진/망/시간/신뢰도/요약으로 갱신되고 DONE, 문서는 DONE + 페이지 수")
    void success_updatesRunAndDocument() {
        writer.saveSuccess(20L, 10L, result(List.of(
                new PageResult(1, PageSource.OCR, "본문", new BigDecimal("0.9100"), 700)), List.of()));

        assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
        assertThat(run.getEngine()).isEqualTo(Engine.TESSERACT);        // AUTO → 실제 사용 엔진
        assertThat(run.getNetworkMode()).isEqualTo(NetworkMode.CLOSED);
        assertThat(run.getTotalMs()).isEqualTo(1234);
        assertThat(run.getAvgConfidence()).isEqualByComparingTo("0.9100");
        assertThat(run.getSummary()).isEqualTo("요약입니다");
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.DONE);
        assertThat(document.getPageCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("페이지는 결과 순서대로 한 번에 저장하며 신뢰도 null(텍스트 레이어)도 그대로 저장")
    void success_savesPages() {
        writer.saveSuccess(20L, 10L, result(List.of(
                new PageResult(1, PageSource.TEXT_LAYER, "a", null, 5),
                new PageResult(2, PageSource.OCR, "b", new BigDecimal("0.8000"), 900)), List.of()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<OcrPage>> captor = ArgumentCaptor.forClass((Class<Iterable<OcrPage>>) (Class<?>) Iterable.class);
        verify(pageRepository).saveAll(captor.capture());
        List<OcrPage> saved = new ArrayList<>();
        captor.getValue().forEach(saved::add);
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getPageNo()).isEqualTo(1);
        assertThat(saved.get(0).getConfidence()).isNull();
        assertThat(saved.get(0).getSource()).isEqualTo(PageSource.TEXT_LAYER);
        assertThat(saved.get(1).getText()).isEqualTo("b");
        assertThat(saved.get(1).getRunId()).isEqualTo(20L);
    }

    @Test
    @DisplayName("이미지는 images/{문서}/{run}/{순번}.{형식} 경로의 파일로 저장하고 DB 에는 경로만 남긴다")
    void success_savesImagesToFiles() {
        byte[] data = {1, 2, 3};
        writer.saveSuccess(20L, 10L, result(List.of(), List.of(
                new ImageResult(1, "PNG ", 64, 48, data),
                new ImageResult(2, "jpg", 10, 10, data))));

        verify(storage).storeBytes("images/10/20/1.png", data);   // 대문자·공백 정규화
        verify(storage).storeBytes("images/10/20/2.jpg", data);
        ArgumentCaptor<OcrImage> img = ArgumentCaptor.forClass(OcrImage.class);
        verify(imageRepository, org.mockito.Mockito.times(2)).save(img.capture());
        assertThat(img.getAllValues().get(0).getFilePath()).isEqualTo("images/10/20/1.png");
        assertThat(img.getAllValues().get(0).getFormat()).isEqualTo("png");
        assertThat(img.getAllValues().get(1).getPageNo()).isEqualTo(2);
    }

    @Test
    @DisplayName("이미지 형식이 null/공백이면 png 로 간주")
    void success_blankImageFormat_defaultsToPng() {
        byte[] data = {9};
        writer.saveSuccess(20L, 10L, result(List.of(), List.of(
                new ImageResult(1, null, 1, 1, data),
                new ImageResult(1, "  ", 1, 1, data))));

        verify(storage).storeBytes("images/10/20/1.png", data);
        verify(storage).storeBytes("images/10/20/2.png", data);
    }

    @Test
    @DisplayName("이미지가 없으면 파일을 저장하지 않는다")
    void success_noImages_noFileWrites() {
        writer.saveSuccess(20L, 10L, result(List.of(), List.of()));

        verify(storage, never()).storeBytes(anyString(), any());
        verify(imageRepository, never()).save(any());
    }

    @Test
    @DisplayName("run 이 사라졌으면 예외 (호출한 리스너가 FAILED 처리)")
    void success_runMissing_throws() {
        when(runRepository.findById(20L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> writer.saveSuccess(20L, 10L, result(List.of(), List.of())))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("실패: run 은 FAILED + 사유 + 종료 시각, 문서는 FAILED")
    void failure_marksRunAndDocument() {
        writer.saveFailure(20L, 10L, "OCR_SERVER_ERROR: 연결 실패");

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorMessage()).isEqualTo("OCR_SERVER_ERROR: 연결 실패");
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    @DisplayName("실패 기록 시 run 이 이미 없어도 예외 없이 문서는 FAILED 로 바꾼다")
    void failure_runMissing_stillFailsDocument() {
        when(runRepository.findById(20L)).thenReturn(Optional.empty());

        writer.saveFailure(20L, 10L, "x");

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    @DisplayName("실패 기록 시 문서가 이미 없어도(삭제) 예외가 나지 않는다")
    void failure_documentMissing_noError() {
        when(documentRepository.findById(10L)).thenReturn(Optional.empty());

        writer.saveFailure(20L, 10L, "x");

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    }
}
