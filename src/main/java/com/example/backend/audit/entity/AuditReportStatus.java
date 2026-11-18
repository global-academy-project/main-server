package com.example.backend.audit.entity;

/** 감사보고서 상태 (V11 ck_audr_status): AI 요약 중 GENERATING / 수치 검증 통과 DRAFT / 불일치 있어 사람이 봐야 함 REVIEW_REQUIRED / AI 실패 FAILED */
public enum AuditReportStatus {
    GENERATING("요약 중"), DRAFT("초안"), REVIEW_REQUIRED("검토 필요"), FAILED("요약 실패");

    private final String label;

    AuditReportStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
