package com.example.backend.job.entity;

/** 비동기 작업 대상 기록 종류. V6 async_jobs.target_type CHECK 와 같은 값. */
public enum JobTarget {
    RISK, PERMIT, INSPECTION, TBM, DAILY_LOG, WORK, SITE
}
