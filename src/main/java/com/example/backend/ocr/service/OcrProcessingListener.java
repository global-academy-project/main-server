package com.example.backend.ocr.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.document.entity.Document;
import com.example.backend.document.repository.DocumentRepository;
import com.example.backend.document.storage.LocalFileStorage;
import com.example.backend.ocr.client.OcrClient;
import com.example.backend.ocr.client.OcrResult;
import com.example.backend.ocr.entity.OcrRun;
import com.example.backend.ocr.repository.OcrRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 커밋 이후(AFTER_COMMIT) 별도 스레드에서 OCR 을 호출하고 결과 저장은 OcrResultWriter 에 맡긴다.
 * OCR 호출은 오래 걸리므로 트랜잭션 밖에서 수행한다.
 */
@Component
public class OcrProcessingListener {

    private static final Logger log = LoggerFactory.getLogger(OcrProcessingListener.class);

    private final OcrRunRepository runRepository;
    private final DocumentRepository documentRepository;
    private final LocalFileStorage storage;
    private final OcrClient ocrClient;
    private final OcrResultWriter writer;

    public OcrProcessingListener(OcrRunRepository runRepository,
                                 DocumentRepository documentRepository,
                                 LocalFileStorage storage,
                                 OcrClient ocrClient,
                                 OcrResultWriter writer) {
        this.runRepository = runRepository;
        this.documentRepository = documentRepository;
        this.storage = storage;
        this.ocrClient = ocrClient;
        this.writer = writer;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(OcrRequestedEvent event) {
        OcrRun run = runRepository.findById(event.runId()).orElse(null);
        Document document = documentRepository.findById(event.documentId()).orElse(null);
        if (run == null || document == null) {
            log.warn("OCR 대상이 없습니다. runId={}, documentId={}", event.runId(), event.documentId());
            return;
        }

        try {
            OcrResult result = ocrClient.extract(
                    storage.resolve(document.getStoredPath()), run.getEngine(), run.isExtractImages());
            writer.saveSuccess(run.getId(), document.getId(), result);
        } catch (Throwable e) {
            log.error("OCR 처리 실패. runId={}", run.getId(), e);
            // 중간에 저장된 이미지 파일이 있으면 정리
            storage.deleteDirectory("images/" + document.getId() + "/" + run.getId());
            try {
                writer.saveFailure(run.getId(), document.getId(), failureMessage(e));
            } catch (Exception inner) {
                log.error("실패 상태 저장에 실패했습니다. runId={}", run.getId(), inner);
            }
        }
    }

    /** 명세: errorMessage 는 "{code}: {message}" 형식 */
    private String failureMessage(Throwable e) {
        if (e instanceof BusinessException be) {
            return be.getErrorCode().name() + ": " + be.getMessage();
        }
        String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return "OCR_FAILED: " + detail;
    }
}
