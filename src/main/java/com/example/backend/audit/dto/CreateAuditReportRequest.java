package com.example.backend.audit.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * 감사보고서 만들기. queryTypes: risk_assessment_rate, permit_approval_statistics, corrective_action_rate,
 * blocked_records, missing_measurements 중 하나 이상 (중복 불가). 기간은 양 끝 포함, app.records.max-days 일까지
 */
public record CreateAuditReportRequest(@NotNull LocalDate dateFrom, @NotNull LocalDate dateTo,
                                       @NotEmpty List<String> queryTypes) {
}
