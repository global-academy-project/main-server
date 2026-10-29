package com.example.backend.work.entity;

import com.example.backend.rule.entity.WorkType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 작업의 고위험 요인 (화기·밀폐·고소). 테이블 규칙은 V3 work_risk_factors.
 * 작업 하나에 요인이 여럿일 수 있다 (예: 탱크 안 용접 = CONFINED + HOT) → 요인마다 허가서가 필요.
 * works.work_type 은 대표 유형 하나, 이 테이블은 전체 목록.
 */
@Entity
@Table(name = "work_risk_factors")
@IdClass(WorkRiskFactorId.class)
public class WorkRiskFactor {

    @Id
    @Column(name = "work_id", nullable = false, updatable = false)
    private Long workId;

    @Id
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20, updatable = false)
    private WorkType factor;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "detected_by", nullable = false, length = 10)
    private DetectedBy detectedBy = DetectedBy.AGENT;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected WorkRiskFactor() {
    }

    public WorkRiskFactor(Long workId, WorkType factor, DetectedBy detectedBy) {
        if (workId == null || factor == null) {
            throw new IllegalArgumentException("작업과 요인이 필요합니다.");
        }
        if (factor == WorkType.GENERAL) {
            throw new IllegalArgumentException("고위험 요인은 HOT·CONFINED·HEIGHT 만 넣을 수 있습니다.");
        }
        this.workId = workId;
        this.factor = factor;
        this.detectedBy = detectedBy == null ? DetectedBy.AGENT : detectedBy;
    }

    public Long getWorkId() { return workId; }
    public WorkType getFactor() { return factor; }
    public DetectedBy getDetectedBy() { return detectedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
