package com.example.backend.inspection.entity;

/** 점검표 상태 (DB ck_insp_status). DRAFT 에서만 내용·항목을 바꿀 수 있다 (V9 trg_insp_lock, trg_ii_lock_*) */
public enum InspectionStatus {
    DRAFT, PENDING, APPROVED, REJECTED;

    public boolean isEditable() {
        return this == DRAFT;
    }
}
