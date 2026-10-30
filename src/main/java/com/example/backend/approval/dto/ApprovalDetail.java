package com.example.backend.approval.dto;

import com.example.backend.approval.entity.ApprovalStepStatus;
import com.example.backend.approval.entity.ApproverRole;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 결재 상세: 한 줄 정보 + 단계 + 반려 때 고른 누락 항목 + 내가 할 수 있는 일.
 * canDecide: 지금 단계를 내가 결재할 수 있음 / canCancel: 내가 요청자이고 아직 대기 중
 */
public record
ApprovalDetail(ApprovalSummary summary, List<Step> steps, List<String> missingItems,
                             boolean canDecide, boolean canCancel) {

    public record Step(int stepNo, String label, ApproverRole requiredRole, Long assigneeId, ApprovalStepStatus status,
                       Long decidedBy, String decidedByName, LocalDateTime decidedAt, String comment) {
    }
}
