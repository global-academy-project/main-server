package com.example.backend.job.entity;

/** 비동기 작업 상태. DONE·FAILED·CANCELED 는 끝난 상태 (finished_at 필수). */
public enum JobStatus {
    QUEUED, RUNNING, DONE, FAILED, CANCELED
}
