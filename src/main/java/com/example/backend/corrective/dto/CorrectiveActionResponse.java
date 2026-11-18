package com.example.backend.corrective.dto;

import com.example.backend.corrective.entity.ActionSource;
import com.example.backend.corrective.entity.ActionStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 개선조치. overdue: 끝나지 않았는데 기한(당일 포함 전까지)이 지났으면 true.
 * 이름(…Name)은 계정 이름, 없으면 null.
 */
public record CorrectiveActionResponse(Long actionId, Long siteId, ActionSource sourceType, Long sourceId,
                                       String title, String description, Long assigneeId, String assigneeName,
                                       LocalDate dueDate, ActionStatus status, boolean overdue,
                                       LocalDateTime completedAt, String completionNote,
                                       Long verifiedBy, String verifiedByName, LocalDateTime verifiedAt,
                                       Long createdBy, String createdByName, LocalDateTime createdAt, int lockVersion) {
}
