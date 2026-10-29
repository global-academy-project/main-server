package com.example.backend.notification.entity;

/** 알림을 누르면 이동할 기록 종류. V6 notifications.target_type CHECK 와 같은 값. */
public enum NotificationTarget {
    WORK, RISK, PERMIT, INSPECTION, TBM, NEAR_MISS, DAILY_LOG, APPROVAL, AGENT_RUN, USER, CORRECTIVE_ACTION, DOCUMENT, JOB
}
