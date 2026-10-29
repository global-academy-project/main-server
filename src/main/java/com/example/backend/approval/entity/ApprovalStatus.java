package com.example.backend.approval.entity;

/** 결재 요청 상태. PENDING 외에는 결정된 상태 (V9 trg_appr_final 로 되돌릴 수 없음). */
public enum ApprovalStatus {
    PENDING, APPROVED, REJECTED, CANCELED
}
