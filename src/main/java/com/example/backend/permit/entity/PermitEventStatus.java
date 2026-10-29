package com.example.backend.permit.entity;

/** 허가 이벤트 상태 (DB ck_pe_status). 결정(APPROVED·REJECTED) 후에는 바꿀 수 없다 (V9 trg_pe_final) */
public enum PermitEventStatus {
    REQUESTED, APPROVED, REJECTED
}
