package com.example.backend.notification.entity;

/** 알림 종류. V6 notifications.type·notification_preferences.type CHECK 와 같은 값. */
public enum NotificationType {
    APPROVAL_REQUEST, APPROVAL_RESULT, BLOCKED, NEAR_MISS_WARNING, DEADLINE, ACTION_DUE, AGENT_DONE, DOCUMENT_READY, JOB_FAILED, ACCOUNT, SYSTEM
}
