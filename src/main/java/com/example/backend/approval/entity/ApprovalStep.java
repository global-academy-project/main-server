package com.example.backend.approval.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 결재선의 한 단계. 테이블 규칙은 V6 approval_steps.
 * <ul>
 *   <li>요청할 때 결재선 정책(ApprovalPolicy)에서 복사해 만든다. 1단계는 PENDING, 나머지는 WAITING</li>
 *   <li>한 결재에 PENDING 단계는 하나 (V6 uk_aps_pending). 다음 단계를 열기 전에 현재 단계 결정을 먼저 flush 한다</li>
 *   <li>결정(APPROVED/REJECTED/SKIPPED)되면 바꿀 수 없음 (V9 trg_aps_final)</li>
 * </ul>
 */
@Entity
@Table(name = "approval_steps")
public class ApprovalStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "approval_id", nullable = false, updatable = false)
    private Long approvalId;

    @Column(name = "step_no", nullable = false, updatable = false)
    private int stepNo;

    /** 예: 부서장 확인, 안전관리자 승인 */
    @Column(nullable = false, length = 50, updatable = false)
    private String label;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "required_role", nullable = false, length = 20, updatable = false)
    private ApproverRole requiredRole;

    /** 특정인 지정 시 (지정 결재함) */
    @Column(name = "assignee_id", updatable = false)
    private Long assigneeId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private ApprovalStepStatus status;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at", columnDefinition = "datetime(6)")
    private LocalDateTime decidedAt;

    @Column(length = 500)
    private String comment;

    protected ApprovalStep() {
    }

    public ApprovalStep(Approval approval, int stepNo, String label, ApproverRole requiredRole, Long assigneeId) {
        if (approval == null || approval.getId() == null || requiredRole == null) {
            throw new IllegalArgumentException("저장된 결재와 결재 역할이 필요합니다.");
        }
        if (stepNo < 1 || stepNo > 10) {
            throw new IllegalArgumentException("결재 단계는 1~10 입니다.");
        }
        this.approvalId = approval.getId();
        this.stepNo = stepNo;
        this.label = ApprovalPolicy.requiredLabel(label);
        this.requiredRole = requiredRole;
        this.assigneeId = assigneeId;
        this.status = stepNo == 1 ? ApprovalStepStatus.PENDING : ApprovalStepStatus.WAITING;
    }

    /** 앞 단계가 승인되어 내 차례 */
    public void open() {
        if (status != ApprovalStepStatus.WAITING) {
            throw new IllegalStateException("대기 중인 단계만 열 수 있습니다 (현재 " + status + ").");
        }
        this.status = ApprovalStepStatus.PENDING;
    }

    public void approve(Long userId, String comment, LocalDateTime now) {
        decide(ApprovalStepStatus.APPROVED, userId, Approval.optionalComment(comment), now);
    }

    /** 반려. 사유 필수 (V6 ck_aps_reason) */
    public void reject(Long userId, String reason, LocalDateTime now) {
        decide(ApprovalStepStatus.REJECTED, userId, Approval.requiredReason(reason), now);
    }

    /** 앞 단계에서 반려되거나 결재가 취소되어 처리할 필요 없음 */
    public void skip() {
        if (isDecided()) {
            throw new IllegalStateException("이미 결정된 단계입니다 (현재 " + status + ").");
        }
        this.status = ApprovalStepStatus.SKIPPED;
    }

    public boolean isDecided() {
        return status == ApprovalStepStatus.APPROVED || status == ApprovalStepStatus.REJECTED
                || status == ApprovalStepStatus.SKIPPED;
    }

    private void decide(ApprovalStepStatus result, Long userId, String comment, LocalDateTime now) {
        if (status != ApprovalStepStatus.PENDING) {
            throw new IllegalStateException("지금 차례인 단계만 결정할 수 있습니다 (현재 " + status + ").");
        }
        if (userId == null || now == null) {
            throw new IllegalArgumentException("결정한 사람과 시각이 필요합니다.");
        }
        if (assigneeId != null && !assigneeId.equals(userId)) {
            throw new IllegalArgumentException("이 단계는 지정된 결재자만 처리할 수 있습니다.");
        }
        this.status = result;
        this.decidedBy = userId;
        this.decidedAt = now;
        this.comment = comment;
    }

    public Long getId() { return id; }
    public Long getApprovalId() { return approvalId; }
    public int getStepNo() { return stepNo; }
    public String getLabel() { return label; }
    public ApproverRole getRequiredRole() { return requiredRole; }
    public Long getAssigneeId() { return assigneeId; }
    public ApprovalStepStatus getStatus() { return status; }
    public Long getDecidedBy() { return decidedBy; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public String getComment() { return comment; }
}
