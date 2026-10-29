package com.example.backend.history.entity;

/** 열람 기록 대상. V6 record_access_logs.resource_type CHECK 와 같은 값. */
public enum AccessResource {
    USER_PII, WORKER_PII, AUDIT_REPORT, RECORD_QUERY, DOCUMENT
}
