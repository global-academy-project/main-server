package com.example.backend.nearmiss.entity;

/**
 * 아차사고 상태 (DB ck_nm_status).
 * REPORTED 보고됨 / TAGGED 5축 분류 완료 (AI 또는 사람) / REVIEWED 관리자 검토 / CLOSED 종결
 */
public enum NearMissStatus {
    REPORTED, TAGGED, REVIEWED, CLOSED
}
