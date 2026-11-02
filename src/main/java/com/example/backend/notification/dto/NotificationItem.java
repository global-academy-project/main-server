package com.example.backend.notification.dto;

import com.example.backend.notification.entity.NotificationSeverity;
import com.example.backend.notification.entity.NotificationTarget;
import com.example.backend.notification.entity.NotificationType;

import java.time.LocalDateTime;

/**
 * 내 알림 한 건. id 는 받는 사람 행(notification_recipients) id 라서 읽음·끄기에 그대로 쓴다.
 * targetType·targetId 로 화면 이동 (없으면 이동 없음)
 */
public record NotificationItem(Long id, Long notificationId, Long siteId, NotificationType type,
                               NotificationSeverity severity, String title, String message,
                               NotificationTarget targetType, Long targetId, LocalDateTime createdAt,
                               LocalDateTime readAt, LocalDateTime expiresAt) {
}
