package com.example.backend.work.entity;

/** 작업 상태 (DB ck_works_status). 승인·진행·완료·취소 후에는 내용을 고칠 수 없다 (V9 trg_works_lock) */
public enum WorkStatus {
    DRAFT, PENDING, APPROVED, REJECTED, BLOCKED, IN_PROGRESS, DONE, CANCELED
}
