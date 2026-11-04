package com.example.backend.dailylog.dto;

import com.example.backend.approval.entity.ApprovalStatus;
import com.example.backend.dailylog.entity.DailyLogStatus;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 일일 안전일지 상세.
 *  - stats: 그날 집계 숫자 (작업·허가·TBM·점검표·개선조치·아차사고·미완료 건수, 팀별)
 *  - checks: 미완료·지연 항목. 대시보드 items[] 와 같은 모양 {type, label, count, overdue, entries[]} (건수 0 은 뺌)
 *  - evidence: 요약의 근거로 쓴 기록 {targetType, targetId, title, detail}
 * stats·checks·evidence 는 초안을 만든 시점의 집계를 그대로 보관한다 (승인 후에도 그때 값 그대로).
 * 승인자·단계별 시각은 GET /api/v1/approvals/{approvalId}.
 */
public record DailyLogResponse(Long dailyLogId, Long siteId, LocalDate date, DailyLogStatus status, String summary,
                               JsonNode stats, JsonNode checks, JsonNode evidence, Long writerId, String writerName,
                               Long agentRunId, Long approvalId, ApprovalStatus approvalStatus, boolean canSubmit,
                               LocalDateTime createdAt, LocalDateTime updatedAt, int lockVersion) {
}
