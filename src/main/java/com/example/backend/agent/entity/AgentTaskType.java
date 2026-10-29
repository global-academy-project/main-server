package com.example.backend.agent.entity;

/** 에이전트 실행 종류 (DB ck_ar_task) */
public enum AgentTaskType {
    WORK_REGISTER, RISK_DRAFT, PERMIT_DRAFT, INSPECTION_DRAFT, TBM_DRAFT,
    NEAR_MISS_TAG, PHOTO_CHECK, RECORD_QUERY, DAILY_LOG, AUDIT_REPORT
}
