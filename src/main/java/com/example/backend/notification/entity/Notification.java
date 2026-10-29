package com.example.backend.notification.entity;

import com.example.backend.history.entity.ActorType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 알림 내용 1건. 받는 사람별 읽음·끄기는 NotificationRecipient. 테이블 규칙은 V6 notifications.
 * 만든 뒤에는 바꾸지 않는다 (모든 컬럼 updatable = false).
 * 업무 트랜잭션 안에서 두 테이블에 저장 → 커밋 후 푸시. 푸시가 실패해도 행이 남아 재접속 때 받는다.
 * <p>
 * 사용 예: {@code Notification.of(siteId, APPROVAL_RESULT, INFO, "작업허가 승인", "...", now).about(PERMIT, 5001).by(USER, 7)}
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 시스템 공지는 null */
    @Column(name = "site_id", updatable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30, updatable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10, updatable = false)
    private NotificationSeverity severity;

    @Column(nullable = false, length = 100, updatable = false)
    private String title;

    @Column(nullable = false, length = 500, updatable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_type", length = 20, updatable = false)
    private NotificationTarget targetType;

    @Column(name = "target_id", updatable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "actor_type", nullable = false, length = 10, updatable = false)
    private ActorType actorType = ActorType.SYSTEM;

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    /** 같은 알림 중복 방지. 예: DEADLINE:TBM:1001:2026-10-26 */
    @Column(name = "dedupe_key", length = 120, updatable = false)
    private String dedupeKey;

    /** 지나면 화면에서 안 보임 (마감 알림 등) */
    @Column(name = "expires_at", updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime expiresAt;

    /** 만료 시각과 비교하므로 앱 시각으로 넣는다 (V6 ck_noti_expires) */
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected Notification() {
    }

    private Notification(Long siteId, NotificationType type, NotificationSeverity severity, String title,
                         String message, LocalDateTime now) {
        if (type == null || severity == null || now == null) {
            throw new IllegalArgumentException("알림 종류·중요도·시각이 필요합니다.");
        }
        this.siteId = siteId;
        this.type = type;
        this.severity = severity;
        this.title = required(title, 100, "알림 제목");
        this.message = required(message, 500, "알림 내용");
        this.createdAt = now;
    }

    public static Notification of(Long siteId, NotificationType type, NotificationSeverity severity,
                                  String title, String message, LocalDateTime now) {
        return new Notification(siteId, type, severity, title, message, now);
    }

    /** 누르면 이동할 기록 */
    public Notification about(NotificationTarget target, Long id) {
        requireNew();
        if (target == null || id == null) {
            throw new IllegalArgumentException("이동할 기록 종류와 id 가 함께 필요합니다.");
        }
        this.targetType = target;
        this.targetId = id;
        return this;
    }

    /** 알림을 일으킨 주체. SYSTEM 이 아니면 id 필수 */
    public Notification by(ActorType actorType, Long actorId) {
        requireNew();
        if (actorType == null || (actorType != ActorType.SYSTEM && actorId == null)) {
            throw new IllegalArgumentException("사용자·에이전트 알림은 행위자 id 가 필요합니다.");
        }
        this.actorType = actorType;
        this.actorId = actorType == ActorType.SYSTEM ? null : actorId;
        return this;
    }

    public Notification dedupeKey(String key) {
        requireNew();
        this.dedupeKey = required(key, 120, "중복 방지 키");
        return this;
    }

    public Notification expiresAt(LocalDateTime at) {
        requireNew();
        if (at == null || !at.isAfter(createdAt)) {
            throw new IllegalArgumentException("만료 시각은 알림 시각보다 뒤여야 합니다.");
        }
        this.expiresAt = at;
        return this;
    }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt != null && now != null && !now.isBefore(expiresAt);
    }

    private void requireNew() {
        if (id != null) {
            throw new IllegalStateException("저장된 알림은 바꿀 수 없습니다.");
        }
    }

    private static String required(String value, int max, String what) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new IllegalArgumentException(what + "이(가) 필요합니다 (" + max + "자 이내).");
        }
        return value.trim();
    }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public NotificationType getType() { return type; }
    public NotificationSeverity getSeverity() { return severity; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public NotificationTarget getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public ActorType getActorType() { return actorType; }
    public Long getActorId() { return actorId; }
    public String getDedupeKey() { return dedupeKey; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
