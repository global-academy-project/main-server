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
 * composeResult: 구성기 응답 원본 (audit_reports.compose_json, 11/20). 상세에서만, 옛 응답·실패·V12 전 보고서는 null.
 *   이 값이 있으면 화면이 서술형 보고서 문서를 보일 수 있다 (B 답변 4장, 필드 이름은 React 가 읽는 composeResult)
 */
public record AuditReportResponse(Long reportId, Long siteId, String title, LocalDate dateFrom, LocalDate dateTo,
                                  List<String> queryTypes, AuditReportStatus status, String statusLabel,
                                  String overallVerification, String failureReason, String summary,
                                  JsonNode items, JsonNode composeResult, Long createdBy, String createdByName,
                                  LocalDateTime createdAt, LocalDateTime updatedAt, int lockVersion,
                                  boolean summaryRetryable) {
}
