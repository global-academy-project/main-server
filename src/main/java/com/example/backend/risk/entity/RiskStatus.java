package com.example.backend.risk.entity;

/**
 * 위험성평가 상태 (DB ck_risk_status).
 * DRAFT 에서만 내용·위험요인을 바꿀 수 있다 (V9 trg_risk_lock, trg_rai_lock_*).
 * 반려 후 다시 쓰면 revision 을 올린 새 행을 만든다 (이전 판은 SUPERSEDED 로 남음).
 */
public enum RiskStatus {
    DRAFT, PENDING, APPROVED, REJECTED, SUPERSEDED;

    public boolean isEditable() {
        return this == DRAFT;
    }
}
