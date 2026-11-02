package com.example.backend.notification.dto;

import com.example.backend.notification.entity.NotificationType;
import jakarta.validation.constraints.NotNull;

/** 알림 종류별 받기 설정. mandatory=true 인 종류(안전 필수)는 웹 알림을 끌 수 없다 */
public record NotificationPreferenceItem(@NotNull NotificationType type, boolean webEnabled, boolean smsEnabled,
                                         boolean mandatory) {
}
