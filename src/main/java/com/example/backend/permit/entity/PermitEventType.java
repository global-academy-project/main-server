package com.example.backend.permit.entity;

/**
 * 승인 후 허가 이벤트 (DB ck_pe_type).
 * EXTEND 연장 (new_valid_to 필수) / SUSPEND 중단 / RESUME 재개 (중단된 허가만) / HANDOVER 인수인계 (handover_to 필수)
 * / CLOSE_CHECK 작업 완료 확인. RESUME 외에는 승인된 허가에만 (V9 trg_pe_ins)
 */
public enum PermitEventType {
    EXTEND, SUSPEND, RESUME, HANDOVER, CLOSE_CHECK
}
