package com.example.backend.dailylog.dto;

import com.example.backend.approval.entity.ApprovalStatus;
import com.example.backend.dailylog.entity.DailyLogStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 안전일지 목록 한 줄 (상세에서 stats·checks·evidence 를 뺀 모양) */
public record DailyLogListItem(Long dailyLogId, Long siteId, LocalDate date, DailyLogStatus status, String summary,
                               Long writerId, String writerName, Long agentRunId, Long approvalId,
                               ApprovalStatus approvalStatus, LocalDateTime createdAt, LocalDateTime updatedAt,
                               int lockVersion) {
}
