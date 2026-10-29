package com.example.backend.tbm.entity;

/**
 * TBM 상태 (DB ck_tbm_status).
 * DRAFT 작성 중 / DONE 실시 완료 (실시 시각·인원 필수) / APPROVED 승인 → 이후 수정·참석자·작업 변경 불가 (V9 trg_tbm_lock 등)
 */
public enum TbmStatus {
    DRAFT, DONE, APPROVED;

    public boolean isEditable() {
        return this != APPROVED;
    }
}
