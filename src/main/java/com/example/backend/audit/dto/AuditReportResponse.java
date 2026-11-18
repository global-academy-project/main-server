package com.example.backend.audit.dto;

import com.example.backend.audit.entity.AuditReportStatus;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 감사보고서. items 는 목록 조회 때 비운다(상세에서만).
 * items[]: {queryType, title, summary, verification{status, expectedNumbers, actualNumbers, mismatches} | null,
 *           stats, total, truncated, evidence[{type, id}]}
 * summaryRetryable: 지금 AI 요약 다시 하기를 할 수 있는지 (요약 실패, 또는 요약 중으로 app.agent.stale-after-seconds 넘게 멈춤)
 */
public record AuditReportResponse(Long reportId, Long siteId, String title, LocalDate dateFrom, LocalDate dateTo,
                                  List<String> queryTypes, AuditReportStatus status, String statusLabel,
                                  String overallVerification, String failureReason, String summary,
                                  JsonNode items, Long createdBy, String createdByName,
                                  LocalDateTime createdAt, LocalDateTime updatedAt, int lockVersion,
                                  boolean summaryRetryable) {
}
