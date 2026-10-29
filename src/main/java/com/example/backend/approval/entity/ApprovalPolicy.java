package com.example.backend.approval.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 결재선 정책: 현장 × 문서 종류마다 단계 목록 (행 1개 = 단계 1개). 테이블 규칙은 V6 approval_policies.
 * 행이 없으면 기본 1단계(APPROVER). 정책을 바꿔도 진행 중인 결재는 요청 때 복사한 단계 그대로.
 */
@Entity
@Table(name = "approval_policies")
@IdClass(ApprovalPolicyId.class)
public class ApprovalPolicy {

    @Id
    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Id
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_type", nullable = false, length = 20, updatable = false)
    private ApprovalTarget targetType;

    @Id
    @Column(name = "step_no", nullable = false, updatable = false)
    private int stepNo;

    @Column(nullable = false, length = 50)
    private String label;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "required_role", nullable = false, length = 20)
    private ApproverRole requiredRole;

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected ApprovalPolicy() {
    }

    public ApprovalPolicy(Long siteId, ApprovalTarget targetType, int stepNo, String label,
                          ApproverRole requiredRole, Long updatedBy) {
        if (siteId == null || targetType == null) {
            throw new IllegalArgumentException("현장과 문서 종류가 필요합니다.");
        }
        if (stepNo < 1 || stepNo > 10) {
            throw new IllegalArgumentException("결재 단계는 1~10 입니다.");
        }
        this.siteId = siteId;
        this.targetType = targetType;
        this.stepNo = stepNo;
        change(label, requiredRole, updatedBy);
    }

    public void change(String label, ApproverRole requiredRole, Long updatedBy) {
        if (requiredRole == null || updatedBy == null) {
            throw new IllegalArgumentException("결재 역할과 변경한 사람이 필요합니다.");
        }
        this.label = requiredLabel(label);
        this.requiredRole = requiredRole;
        this.updatedBy = updatedBy;
    }

    static String requiredLabel(String label) {
        if (label == null || label.isBlank() || label.trim().length() > 50) {
            throw new IllegalArgumentException("단계 이름이 필요합니다 (50자 이내).");
        }
        return label.trim();
    }

    public Long getSiteId() { return siteId; }
    public ApprovalTarget getTargetType() { return targetType; }
    public int getStepNo() { return stepNo; }
    public String getLabel() { return label; }
    public ApproverRole getRequiredRole() { return requiredRole; }
    public Long getUpdatedBy() { return updatedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
