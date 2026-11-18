package com.example.backend.audit.service;

import com.example.backend.config.AsyncConfig;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 감사보고서 AI 요약: 요청 트랜잭션이 커밋된 뒤 에이전트 실행기에서 (AI 응답 대기가 길어 웹 요청과 분리) */
@Component
public class AuditReportSummaryListener {

    private final AuditReportService service;

    public AuditReportSummaryListener(AuditReportService service) {
        this.service = service;
    }

    @Async(AsyncConfig.AGENT_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(AuditReportRequestedEvent event) {
        service.runSummary(event.reportId());
    }
}
