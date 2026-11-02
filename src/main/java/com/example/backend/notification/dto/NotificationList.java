package com.example.backend.notification.dto;

import java.util.List;

/** 알림 목록 + 안 읽은 수 (배지) */
public record NotificationList(List<NotificationItem> items, long unreadCount) {
}
