package com.example.backend.work.entity;

/** 경고 강도 (DB ck_wwarn_sev). CRITICAL 은 확인할 때 조치 내용(ack_note)이 필수 */
public enum WarningSeverity {
    WARN, CRITICAL
}
