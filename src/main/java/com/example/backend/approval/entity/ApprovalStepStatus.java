package com.example.backend.approval.entity;

/** 결재 단계 상태. WAITING(앞 단계 대기) → PENDING(내 차례) → APPROVED/REJECTED, 앞에서 반려·취소되면 SKIPPED. */
public enum ApprovalStepStatus {
    WAITING, PENDING, APPROVED, REJECTED, SKIPPED
}
