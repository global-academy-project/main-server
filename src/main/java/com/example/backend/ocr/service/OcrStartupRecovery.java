package com.example.backend.ocr.service;

import com.example.backend.common.type.DocumentStatus;
import com.example.backend.common.type.RunStatus;
import com.example.backend.document.repository.DocumentRepository;
import com.example.backend.ocr.entity.OcrRun;
import com.example.backend.ocr.repository.OcrRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서버 재시작 시 남아 있는 PROCESSING 을 FAILED 로 정리한다. (명세 §5)
 *
 * 주의: 여러 명이 같은 DB 를 쓰는 개발 환경에서는 다른 사람이 처리 중인 건까지 FAILED 로 바꿔버린다.
 * 그래서 기본은 꺼져 있고, 서버 1대만 쓰는 환경에서 app.ocr.recover-on-startup=true 로 켠다.
 */
@Component
@ConditionalOnProperty(name = "app.ocr.recover-on-startup", havingValue = "true")
public class OcrStartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(OcrStartupRecovery.class);

    private final OcrRunRepository runRepository;
    private final DocumentRepository documentRepository;

    public OcrStartupRecovery(OcrRunRepository runRepository, DocumentRepository documentRepository) {
        this.runRepository = runRepository;
        this.documentRepository = documentRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recover() {
        var runs = runRepository.findByStatus(RunStatus.PROCESSING);
        runs.forEach((OcrRun r) -> r.fail("서버 재시작으로 중단됨"));
        var docs = documentRepository.findByStatus(DocumentStatus.PROCESSING);
        docs.forEach(d -> d.changeStatus(DocumentStatus.FAILED));
        if (!runs.isEmpty() || !docs.isEmpty()) {
            log.warn("재시작으로 중단된 처리를 FAILED 로 정리했습니다. runs={}, documents={}", runs.size(), docs.size());
        }
    }
}
