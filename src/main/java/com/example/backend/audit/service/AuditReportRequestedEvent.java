package com.example.backend.audit.service;

/** 감사보고서 집계 저장 완료 → 커밋 뒤 AI 요약 (AuditReportSummaryListener) */
public record AuditReportRequestedEvent(Long reportId) {
}
