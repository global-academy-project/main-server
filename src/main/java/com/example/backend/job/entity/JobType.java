package com.example.backend.job.entity;

/** 비동기 작업 종류. V6 async_jobs.job_type CHECK 와 같은 값. */
public enum JobType {
    EXPORT_PDF, RULE_CHECK, AUDIT_REPORT, DAILY_LOG_BUILD
}
