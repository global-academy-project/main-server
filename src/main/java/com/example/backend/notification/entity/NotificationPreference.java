package com.example.backend.notification.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * 사용자별 알림 설정 (사용자 × 종류). 행이 없으면 기본값(웹 켜짐, 문자 꺼짐). 테이블 규칙은 V6 notification_preferences.
 * 안전 관련 필수 알림(차단, 아차사고 경고, 승인 요청)은 웹 알림을 끌 수 없다 (V6 ck_np_mandatory).
 */
@Entity
@Table(name = "notification_preferences")
@IdClass(NotificationPreferenceId.class)
public class NotificationPreference {

    /** 웹 알림을 끌 수 없는 종류 */
    public static final Set<NotificationType> MANDATORY = EnumSet.of(
            NotificationType.BLOCKED, NotificationType.NEAR_MISS_WARNING, NotificationType.APPROVAL_REQUEST);

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Id
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30, updatable = false)
    private NotificationType type;

    @Column(name = "web_enabled", nullable = false)
    private boolean webEnabled = true;

    /** 문자 발송은 이후 확장 (users.phone 사용) */
    @Column(name = "sms_enabled", nullable = false)
    private boolean smsEnabled;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected NotificationPreference() {
    }

    public NotificationPreference(Long userId, NotificationType type, boolean webEnabled, boolean smsEnabled) {
        if (userId == null || type == null) {
            throw new IllegalArgumentException("사용자와 알림 종류가 필요합니다.");
        }
        this.userId = userId;
        this.type = type;
        change(webEnabled, smsEnabled);
    }

    public void change(boolean webEnabled, boolean smsEnabled) {
        if (!webEnabled && MANDATORY.contains(type)) {
            throw new IllegalArgumentException("안전 관련 필수 알림은 끌 수 없습니다.");
        }
        this.webEnabled = webEnabled;
        this.smsEnabled = smsEnabled;
    }

    public Long getUserId() { return userId; }
    public NotificationType getType() { return type; }
    public boolean isWebEnabled() { return webEnabled; }
    public boolean isSmsEnabled() { return smsEnabled; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
