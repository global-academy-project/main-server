package com.example.backend.ocr.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.type.DocumentStatus;
import com.example.backend.common.type.Engine;
import com.example.backend.common.type.NetworkMode;
import com.example.backend.document.entity.Document;
import com.example.backend.document.repository.DocumentRepository;
import com.example.backend.document.storage.LocalFileStorage;
import com.example.backend.ocr.client.OcrClient;
import com.example.backend.ocr.client.OcrResult;
import com.example.backend.ocr.entity.OcrRun;
import com.example.backend.ocr.repository.OcrRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.example.backend.support.TestFixtures.document;
import static com.example.backend.support.TestFixtures.run;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("OcrProcessingListener: 커밋 이후 OCR 호출과 성공/실패 기록")
class OcrProcessingListenerTest {

    private static final OcrRequestedEvent EVENT = new OcrRequestedEvent(20L, 10L);

    private OcrRunRepository runRepository;
    private DocumentRepository documentRepository;
    private LocalFileStorage storage;
    private OcrClient ocrClient;
    private OcrResultWriter writer;
    private OcrProcessingListener listener;

    private OcrRun run;
    private Document document;
    private OcrResult result;

    @BeforeEach
    void setUp() {
        runRepository = mock(OcrRunRepository.class);
        documentRepository = mock(DocumentRepository.class);
        storage = mock(LocalFileStorage.class);
        ocrClient = mock(OcrClient.class);
        writer = mock(OcrResultWriter.class);
        listener = new OcrProcessingListener(runRepository, documentRepository, storage, ocrClient, writer);

        run = run(20L, 10L, Engine.OPENAI);
        document = document(10L, 1L, DocumentStatus.PROCESSING);
        result = new OcrResult(Engine.OPENAI, NetworkMode.OPEN, 1, 100, null, List.of(), List.of(), null);

        when(runRepository.findById(20L)).thenReturn(Optional.of(run));
        when(documentRepository.findById(10L)).thenReturn(Optional.of(document));
        when(storage.resolve(document.getStoredPath())).thenReturn(Path.of("/data/a.pdf"));
    }

    @Test
    @DisplayName("성공: 저장된 PDF 경로·엔진·이미지 옵션으로 OCR 을 호출하고 결과를 저장한다")
    void success() {
        when(ocrClient.extract(Path.of("/data/a.pdf"), Engine.OPENAI, false)).thenReturn(result);

        listener.handle(EVENT);

        verify(writer).saveSuccess(20L, 10L, result);
        verify(writer, never()).saveFailure(anyLong(), anyLong(), anyString());
        verify(storage, never()).deleteDirectory(anyString());
    }

    @Test
    @DisplayName("run 이 이미 없으면 OCR 을 호출하지 않고 조용히 끝난다")
    void runMissing_skips() {
        when(runRepository.findById(20L)).thenReturn(Optional.empty());

        listener.handle(EVENT);

        verifyNoInteractions(ocrClient, writer);
    }

    @Test
    @DisplayName("문서가 이미 없으면(삭제됨) OCR 을 호출하지 않는다")
    void documentMissing_skips() {
        when(documentRepository.findById(10L)).thenReturn(Optional.empty());

        listener.handle(EVENT);

        verifyNoInteractions(ocrClient, writer);
    }

    @Test
    @DisplayName("BusinessException 실패: errorMessage 는 \"{코드}: {메시지}\" 형식, 이미지 폴더 정리")
    void businessFailure_recorded() {
        when(ocrClient.extract(any(), any(), anyBoolean())).thenThrow(
                new BusinessException(ErrorCode.OCR_SERVER_ERROR, "OCR 서버에 연결할 수 없습니다."));

        listener.handle(EVENT);

        verify(writer).saveFailure(20L, 10L, "OCR_SERVER_ERROR: OCR 서버에 연결할 수 없습니다.");
        verify(storage).deleteDirectory("images/10/20");
        verify(writer, never()).saveSuccess(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("일반 예외 실패: \"OCR_FAILED: {메시지}\"")
    void runtimeFailure_recorded() {
        when(ocrClient.extract(any(), any(), anyBoolean())).thenThrow(new IllegalStateException("PDF 를 읽을 수 없습니다."));

        listener.handle(EVENT);

        verify(writer).saveFailure(20L, 10L, "OCR_FAILED: PDF 를 읽을 수 없습니다.");
    }

    @Test
    @DisplayName("메시지 없는 예외는 클래스 이름으로 사유를 남긴다")
    void exceptionWithoutMessage_usesClassName() {
        when(ocrClient.extract(any(), any(), anyBoolean())).thenThrow(new NullPointerException());

        listener.handle(EVENT);

        verify(writer).saveFailure(20L, 10L, "OCR_FAILED: NullPointerException");
    }

    @Test
    @DisplayName("Error(예: OOM 계열)도 잡아서 FAILED 로 남긴다 — 문서가 PROCESSING 으로 영원히 남지 않게")
    void errorIsAlsoRecorded() {
        when(ocrClient.extract(any(), any(), anyBoolean())).thenThrow(new StackOverflowError("deep"));

        listener.handle(EVENT);

        verify(writer).saveFailure(eq(20L), eq(10L), eq("OCR_FAILED: deep"));
    }

    @Test
    @DisplayName("결과 저장(DB) 단계에서 실패해도 FAILED 로 기록하고 이미지 폴더를 정리한다")
    void saveSuccessFailure_recordedAsFailure() {
        when(ocrClient.extract(any(), any(), anyBoolean())).thenReturn(result);
        doThrow(new IllegalStateException("db down")).when(writer).saveSuccess(20L, 10L, result);

        listener.handle(EVENT);

        verify(writer).saveFailure(20L, 10L, "OCR_FAILED: db down");
        verify(storage).deleteDirectory("images/10/20");
    }

    @Test
    @DisplayName("실패 기록 자체가 실패해도 예외가 밖으로 나가지 않는다 (@Async 스레드 보호)")
    void failureRecordingFailure_isSwallowed() {
        when(ocrClient.extract(any(), any(), anyBoolean())).thenThrow(new RuntimeException("x"));
        doThrow(new RuntimeException("db also down")).when(writer).saveFailure(anyLong(), anyLong(), anyString());

        assertThatCode(() -> listener.handle(EVENT)).doesNotThrowAnyException();
    }
}
