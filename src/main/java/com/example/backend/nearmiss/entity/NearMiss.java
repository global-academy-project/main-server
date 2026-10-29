package com.example.backend.nearmiss.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 아차사고. 테이블 규칙은 V5 near_misses.
 * 보고 → 5축 태깅(record_tags, NEAR_MISS) → 검토 → 종결. 같은 구역 작업에 경고(work_warnings)로 이어진다 (시나리오 C).
 * 종결 후에는 내용을 고치지 않는다 (DB 트리거는 없음 → 엔티티에서 막음).
 */
@Entity
@Table(name = "near_misses")
public class NearMiss {

    /** 휴대폰 시계 오차 허용: 발생 시각이 보고 시각보다 이만큼까지는 뒤여도 받는다 (DB가 아닌 앱 검증) */
    public static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    /** 발생 구역 (같은 현장). 같은 구역 경고의 기준 */
    @Column(name = "zone_id")
    private Long zoneId;

    /** 관련 작업 (같은 현장) */
    @Column(name = "work_id")
    private Long workId;

    @Column(name = "occurred_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime occurredAt;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "immediate_action", columnDefinition = "text")
    private String immediateAction;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private NearMissStatus status = NearMissStatus.REPORTED;

    @Column(name = "reporter_id", nullable = false, updatable = false)
    private Long reporterId;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected NearMiss() {
    }

    /** now: 보고 시각 (서버 시계). 발생 시각이 now + 5분보다 뒤면 거부 */
    public NearMiss(Long siteId, Long zoneId, Long workId, LocalDateTime occurredAt, String title, String description,
                    String immediateAction, Long reporterId, LocalDateTime now) {
        if (siteId == null || reporterId == null) {
            throw new IllegalArgumentException("현장과 보고자가 필요합니다.");
        }
        this.siteId = siteId;
        this.reporterId = reporterId;
        apply(zoneId, workId, occurredAt, title, description, immediateAction, now);
    }

    public void update(Long zoneId, Long workId, LocalDateTime occurredAt, String title, String description,
                       String immediateAction, LocalDateTime now) {
        requireOpen();
        apply(zoneId, workId, occurredAt, title, description, immediateAction, now);
    }

    private void apply(Long zoneId, Long workId, LocalDateTime occurredAt, String title, String description,
                       String immediateAction, LocalDateTime now) {
        if (occurredAt == null) {
            throw new IllegalArgumentException("발생 시각이 필요합니다.");
        }
        if (now != null && occurredAt.isAfter(now.plus(CLOCK_SKEW))) {
            throw new IllegalArgumentException("발생 시각이 현재보다 뒤입니다.");
        }
        if (title == null || title.isBlank() || description == null || description.isBlank()) {
            throw new IllegalArgumentException("제목과 내용이 필요합니다.");
        }
        this.zoneId = zoneId;
        this.workId = workId;
        this.occurredAt = occurredAt;
        this.title = title.trim();
        this.description = description.trim();
        this.immediateAction = immediateAction;
    }

    private void requireOpen() {
        if (status == NearMissStatus.CLOSED) {
            throw new IllegalStateException("종결된 아차사고는 고칠 수 없습니다.");
        }
    }

    /** 5축 태깅 완료 (record_tags 저장 후) */
    public void markTagged() {
        requireOpen();
        if (status == NearMissStatus.REPORTED) {
            this.status = NearMissStatus.TAGGED;
        }
    }

    /** 관리자 검토 (태그 확인·수정 후) */
    public void review() {
        requireOpen();
        this.status = NearMissStatus.REVIEWED;
    }

    public void close() {
        if (status != NearMissStatus.REVIEWED) {
            throw new IllegalStateException("검토된 아차사고만 종결할 수 있습니다 (현재 " + status + ").");
        }
        this.status = NearMissStatus.CLOSED;
    }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public Long getZoneId() { return zoneId; }
    public Long getWorkId() { return workId; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getImmediateAction() { return immediateAction; }
    public NearMissStatus getStatus() { return status; }
    public Long getReporterId() { return reporterId; }
    public int getLockVersion() { return lockVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
