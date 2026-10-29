package com.example.backend.notification.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 사용자별 알림 수신함 1행. 화면 목록·배지·모두 읽음·끄기는 여기서. 테이블 규칙은 V6 notification_recipients.
 * <ul>
 *   <li>id 는 SSE 이벤트 id 로도 쓴다 (재접속 시 이 id 이후만)</li>
 *   <li>읽음(read_at) = 배지에서 빠짐, 끄기(dismissed_at) = 목록에서 사라짐. 끄면 읽음도 같이 (V6 ck_nr_dismiss)</li>
 *   <li>같은 알림을 한 사람에게 두 번 넣지 않음 (V6 uk_nr_user)</li>
 * </ul>
 */
@Entity
@Table(name = "notification_recipients")
public class NotificationRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notification_id", nullable = false, updatable = false)
    private Long notificationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** 실시간 푸시 성공 시각. null 이면 재접속 때 보냄 */
    @Column(name = "delivered_at", columnDefinition = "datetime(6)")
    private LocalDateTime deliveredAt;

    @Column(name = "read_at", columnDefinition = "datetime(6)")
    private LocalDateTime readAt;

    @Column(name = "dismissed_at", columnDefinition = "datetime(6)")
    private LocalDateTime dismissedAt;

    /** 읽음 시각과 비교하므로 앱 시각으로 넣는다 (V6 ck_nr_order) */
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected NotificationRecipient() {
    }

    public NotificationRecipient(Notification notification, Long userId) {
        if (notification == null || notification.getId() == null || userId == null) {
            throw new IllegalArgumentException("저장된 알림과 받는 사람이 필요합니다.");
        }
        this.notificationId = notification.getId();
        this.userId = userId;
        this.createdAt = notification.getCreatedAt();
    }

    /** 푸시 성공. 이미 보냈으면 그대로 */
    public void markDelivered(LocalDateTime now) {
        if (deliveredAt == null) {
            this.deliveredAt = notNull(now);
        }
    }

    /** 읽음. 이미 읽었으면 그대로 */
    public void read(LocalDateTime now) {
        if (readAt == null) {
            LocalDateTime at = notNull(now);
            this.readAt = at.isBefore(createdAt) ? createdAt : at;
        }
    }

    /** 끄기. 안 읽었으면 읽음도 같이 */
    public void dismiss(LocalDateTime now) {
        read(now);
        if (dismissedAt == null) {
            this.dismissedAt = readAt.isAfter(now) ? readAt : now;
        }
    }

    public boolean isUnread() {
        return readAt == null;
    }

    private static LocalDateTime notNull(LocalDateTime now) {
        if (now == null) {
            throw new IllegalArgumentException("시각이 필요합니다.");
        }
        return now;
    }

    public Long getId() { return id; }
    public Long getNotificationId() { return notificationId; }
    public Long getUserId() { return userId; }
    public LocalDateTime getDeliveredAt() { return deliveredAt; }
    public LocalDateTime getReadAt() { return readAt; }
    public LocalDateTime getDismissedAt() { return dismissedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
