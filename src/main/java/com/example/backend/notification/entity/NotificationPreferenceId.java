package com.example.backend.notification.entity;

import java.io.Serializable;
import java.util.Objects;

/** notification_preferences 복합키 (user_id, type) */
public class NotificationPreferenceId implements Serializable {

    private Long userId;
    private NotificationType type;

    protected NotificationPreferenceId() {
    }

    public NotificationPreferenceId(Long userId, NotificationType type) {
        this.userId = userId;
        this.type = type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NotificationPreferenceId that)) return false;
        return Objects.equals(userId, that.userId) && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, type);
    }
}
