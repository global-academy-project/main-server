package com.example.backend.permit.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 승인된 허가의 연장·중단·재개·인수인계·완료 확인. 테이블 규칙은 V4 permit_events.
 * 요청 → 승인·반려. 요청 내용은 바꾸지 않고(취소 후 다시 요청), 결정 후에는 바꿀 수 없고, 지울 수 없다 (V9 trg_pe_*).
 * 허가 상태 확인(승인된 허가, 재개는 중단된 허가)과 연장 시각 비교(승인된 연장 포함)는 DB 트리거도 막지만 서비스에서 먼저 확인한다.
 */
@Entity
@Table(name = "permit_events")
public class PermitEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "permit_id", nullable = false, updatable = false)
    private Long permitId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "event_type", nullable = false, length = 20, updatable = false)
    private PermitEventType eventType;

    @Column(nullable = false, length = 500, updatable = false)
    private String reason;

    /** EXTEND 일 때 연장 후 종료 시각 */
    @Column(name = "new_valid_to", updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime newValidTo;

    /** HANDOVER 일 때 인수자 (교대) */
    @Column(name = "handover_to", length = 50, updatable = false)
    private String handoverTo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private PermitEventStatus status = PermitEventStatus.REQUESTED;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private Long requestedBy;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at", columnDefinition = "datetime(6)")
    private LocalDateTime decidedAt;

    /** 측정값 이상으로 자동 중단됐다면 그 판정 (rule_evaluations.id) */
    @Column(name = "evaluation_id", updatable = false)
    private Long evaluationId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected PermitEvent() {
    }

    private PermitEvent(Permit permit, PermitEventType type, String reason, LocalDateTime newValidTo,
                        String handoverTo, Long evaluationId, Long requestedBy) {
        if (permit == null || permit.getId() == null || type == null || requestedBy == null) {
            throw new IllegalArgumentException("허가서·이벤트 종류·요청자가 필요합니다.");
        }
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("사유가 필요합니다 (500자 이내).");
        }
        this.permitId = permit.getId();
        this.siteId = permit.getSiteId();
        this.eventType = type;
        this.reason = reason.trim();
        this.newValidTo = newValidTo;
        this.handoverTo = handoverTo;
        this.evaluationId = evaluationId;
        this.requestedBy = requestedBy;
    }

    /** 연장. 새 종료 시각은 지금 종료 시각보다 뒤 (이미 승인된 연장이 있으면 그보다 뒤인지는 서비스·DB가 확인) */
    public static PermitEvent extend(Permit permit, String reason, LocalDateTime newValidTo, Long requestedBy) {
        if (newValidTo == null || (permit != null && !newValidTo.isAfter(permit.getValidTo()))) {
            throw new IllegalArgumentException("연장 시각은 지금 종료 시각보다 뒤여야 합니다.");
        }
        return new PermitEvent(permit, PermitEventType.EXTEND, reason, newValidTo, null, null, requestedBy);
    }

    /** 중단. 측정값 이상으로 자동 중단이면 그 판정 id */
    public static PermitEvent suspend(Permit permit, String reason, Long evaluationId, Long requestedBy) {
        return new PermitEvent(permit, PermitEventType.SUSPEND, reason, null, null, evaluationId, requestedBy);
    }

    public static PermitEvent resume(Permit permit, String reason, Long requestedBy) {
        return new PermitEvent(permit, PermitEventType.RESUME, reason, null, null, null, requestedBy);
    }

    /** 인수인계 (교대). 인수자 이름 필수, 50자 이내 */
    public static PermitEvent handover(Permit permit, String reason, String handoverTo, Long requestedBy) {
        if (handoverTo == null || handoverTo.isBlank() || handoverTo.trim().length() > 50) {
            throw new IllegalArgumentException("인수자가 필요합니다 (50자 이내).");
        }
        return new PermitEvent(permit, PermitEventType.HANDOVER, reason, null, handoverTo.trim(), null, requestedBy);
    }

    /** 작업 완료 확인 */
    public static PermitEvent closeCheck(Permit permit, String reason, Long requestedBy) {
        return new PermitEvent(permit, PermitEventType.CLOSE_CHECK, reason, null, null, null, requestedBy);
    }

    public void approve(Long userId, LocalDateTime now) {
        decide(PermitEventStatus.APPROVED, userId, now);
    }

    public void reject(Long userId, LocalDateTime now) {
        decide(PermitEventStatus.REJECTED, userId, now);
    }

    private void decide(PermitEventStatus result, Long userId, LocalDateTime now) {
        if (status != PermitEventStatus.REQUESTED) {
            throw new IllegalStateException("이미 결정된 허가 이벤트입니다 (현재 " + status + ").");
        }
        if (userId == null || now == null) {
            throw new IllegalArgumentException("결정한 사람과 시각이 필요합니다.");
        }
        this.status = result;
        this.decidedBy = userId;
        this.decidedAt = now;
    }

    public Long getId() { return id; }
    public Long getPermitId() { return permitId; }
    public Long getSiteId() { return siteId; }
    public PermitEventType getEventType() { return eventType; }
    public String getReason() { return reason; }
    public LocalDateTime getNewValidTo() { return newValidTo; }
    public String getHandoverTo() { return handoverTo; }
    public PermitEventStatus getStatus() { return status; }
    public Long getRequestedBy() { return requestedBy; }
    public Long getDecidedBy() { return decidedBy; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public Long getEvaluationId() { return evaluationId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
