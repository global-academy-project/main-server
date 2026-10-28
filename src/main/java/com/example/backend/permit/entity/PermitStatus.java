package com.example.backend.permit.entity;

/**
 * 허가 상태 (DB ck_permits_status).
 *  DRAFT: 작성 중 / BLOCKED: 최근 판정이 BLOCK / PENDING: 승인 요청됨 / APPROVED·REJECTED: 결정
 *  SUSPENDED: 작업 중 중단 / CLOSED: 완료 / SUPERSEDED: 새 판으로 대체 / CHECKING: (예약) 에이전트 판정 중
 * DRAFT·CHECKING·BLOCKED 에서만 내용·측정값을 바꿀 수 있다 (V9 trg_permits_lock, trg_pm_lock_ins)
 */
public enum PermitStatus {
    DRAFT, CHECKING, BLOCKED, PENDING, APPROVED, SUSPENDED, REJECTED, CLOSED, SUPERSEDED;

    public boolean isEditable() {
        return this == DRAFT || this == CHECKING || this == BLOCKED;
    }
}
