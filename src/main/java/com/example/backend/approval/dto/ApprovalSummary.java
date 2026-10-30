package com.example.backend.approval.dto;

import com.example.backend.approval.entity.ApprovalStatus;
import com.example.backend.approval.entity.ApprovalTarget;

import java.time.LocalDateTime;

/**
 * 승인함 한 줄.
 * selfApproval: 요청자와 (최종) 결정자가 같은 사람 (결정 전이면 false). 감사 때 바로 보이게 표시
 */
public record ApprovalSummary(Long approvalId, ApprovalTarget targetType, Long targetId, String title,
                              Long requestedBy, String requestedByName, LocalDateTime requestedAt, LocalDateTime dueAt,
                              boolean overdue, ApprovalStatus status, int currentStep, int totalSteps,
                              String currentStepLabel, String comment, boolean selfApproval) {
}
