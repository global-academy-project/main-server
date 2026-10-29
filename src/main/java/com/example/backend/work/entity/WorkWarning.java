package com.example.backend.work.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 작업에 띄운 경고와 확인 기록 (시나리오 C). 테이블 규칙은 V5 work_warnings.
 * "경고를 보고도 진행했나"를 감사 때 보여 주는 기록이라, 경고 내용은 바꾸지 않고 확인(acknowledge)만 한 번 남긴다.
 * 같은 아차사고로 같은 작업에 경고를 두 번 띄우지 않는다 (DB uk_ww_nm).
 */
@Entity
@Table(name = "work_warnings")
public class WorkWarning {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Column(name = "work_id", nullable = false, updatable = false)
    private Long workId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source_type", nullable = false, length = 20, updatable = false)
    private WarningSource sourceType;

    @Column(name = "near_miss_id", updatable = false)
    private Long nearMissId;

    @Column(name = "rule_evaluation_id", updatable = false)
    private Long ruleEvaluationId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10, updatable = false)
    private WarningSeverity severity = WarningSeverity.WARN;

    @Column(nullable = false, length = 500, updatable = false)
    private String message;

    /** 경고를 띄운 시각 (DB 기본값) */
    @Column(name = "shown_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime shownAt;

    @Column(name = "acknowledged_by")
    private Long acknowledgedBy;

    @Column(name = "acknowledged_at", columnDefinition = "datetime(6)")
    private LocalDateTime acknowledgedAt;

    /** 확인하며 남긴 조치 (예: 안전대 2중 체결) */
    @Column(name = "ack_note", length = 300)
    private String ackNote;

    protected WorkWarning() {
    }

    private WorkWarning(Long siteId, Long workId, WarningSource sourceType, Long nearMissId, Long ruleEvaluationId,
                        WarningSeverity severity, String message) {
        if (siteId == null || workId == null || sourceType == null) {
            throw new IllegalArgumentException("현장·작업·출처가 필요합니다.");
        }
        if ((sourceType == WarningSource.NEAR_MISS) != (nearMissId != null)
                || (sourceType == WarningSource.RULE) != (ruleEvaluationId != null)) {
            throw new IllegalArgumentException("아차사고 경고는 아차사고만, 규칙 경고는 판정만 연결합니다.");
        }
        if (message == null || message.isBlank() || message.length() > 500) {
            throw new IllegalArgumentException("경고 문구가 필요합니다 (500자 이내).");
        }
        this.siteId = siteId;
        this.workId = workId;
        this.sourceType = sourceType;
        this.nearMissId = nearMissId;
        this.ruleEvaluationId = ruleEvaluationId;
        this.severity = severity == null ? WarningSeverity.WARN : severity;
        this.message = message.trim();
    }

    /** 같은 구역 아차사고 경고 */
    public static WorkWarning fromNearMiss(Long siteId, Long workId, Long nearMissId, WarningSeverity severity, String message) {
        return new WorkWarning(siteId, workId, WarningSource.NEAR_MISS, nearMissId, null, severity, message);
    }

    /** 규칙 판정 경고 (rule_evaluations) */
    public static WorkWarning fromRule(Long siteId, Long workId, Long ruleEvaluationId, WarningSeverity severity, String message) {
        return new WorkWarning(siteId, workId, WarningSource.RULE, null, ruleEvaluationId, severity, message);
    }

    /** 구역 위험 메모·자격 경고 */
    public static WorkWarning of(Long siteId, Long workId, WarningSource sourceType, WarningSeverity severity, String message) {
        if (sourceType == WarningSource.NEAR_MISS || sourceType == WarningSource.RULE) {
            throw new IllegalArgumentException("아차사고·규칙 경고는 fromNearMiss / fromRule 로 만듭니다.");
        }
        return new WorkWarning(siteId, workId, sourceType, null, null, severity, message);
    }

    /** 확인. 한 번만, CRITICAL 이면 조치 내용 필수 (DB ck_wwarn_crit) */
    public void acknowledge(Long userId, LocalDateTime now, String note) {
        if (isAcknowledged()) {
            throw new IllegalStateException("이미 확인한 경고입니다.");
        }
        if (userId == null || now == null) {
            throw new IllegalArgumentException("확인한 사람과 시각이 필요합니다.");
        }
        String n = note == null || note.isBlank() ? null : note.trim();
        if (severity == WarningSeverity.CRITICAL && n == null) {
            throw new IllegalArgumentException("긴급 경고는 조치 내용을 적어야 확인할 수 있습니다.");
        }
        if (n != null && n.length() > 300) {
            throw new IllegalArgumentException("조치 내용은 300자 이내입니다.");
        }
        this.acknowledgedBy = userId;
        this.acknowledgedAt = now;
        this.ackNote = n;
    }

    public boolean isAcknowledged() { return acknowledgedAt != null; }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public Long getWorkId() { return workId; }
    public WarningSource getSourceType() { return sourceType; }
    public Long getNearMissId() { return nearMissId; }
    public Long getRuleEvaluationId() { return ruleEvaluationId; }
    public WarningSeverity getSeverity() { return severity; }
    public String getMessage() { return message; }
    public LocalDateTime getShownAt() { return shownAt; }
    public Long getAcknowledgedBy() { return acknowledgedBy; }
    public LocalDateTime getAcknowledgedAt() { return acknowledgedAt; }
    public String getAckNote() { return ackNote; }
}
