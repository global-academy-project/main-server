package com.example.backend.dailylog.entity;

/** 안전일지 상태. APPROVED 후에는 내용 수정 불가 (V9 trg_dsl_lock). */
public enum DailyLogStatus {
    DRAFT, PENDING, APPROVED
}
