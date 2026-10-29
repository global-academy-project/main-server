package com.example.backend.attachment.entity;

/**
 * 첨부가 붙은 기록 종류 (DB ck_att_owner). 다형 참조라 DB 외래키가 없다 → 서비스에서 기록이 있는지(같은 현장) 확인.
 * DRAFT 는 임시저장 중 올린 사진: 정식 저장 때 실제 기록으로 옮기고(moveTo), 버려진 것은 정리 배치가 지운다.
 */
public enum AttachmentOwner {
    WORK, PERMIT, INSPECTION, TBM, NEAR_MISS, WORKER, CORRECTIVE_ACTION, SIGNATURE, DRAFT
}
