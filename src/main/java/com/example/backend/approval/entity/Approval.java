package com.example.backend.approval.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 결재 요청 1건. 결재선의 단계들은 approval_steps (ApprovalStep). 테이블 규칙은 V6 approvals.
 * <ul>
 *   <li>같은 기록에 대기 중(PENDING) 요청은 하나 (V6 uk_appr_pending, 생성 컬럼이라 매핑하지 않음)</li>
 *   <li>결정(APPROVED/REJECTED/CANCELED) 후에는 되돌릴 수 없음 (V9 trg_appr_final) → 다시 요청은 새 행</li>
 *   <li>작성자 = 승인자 허용 여부는 현장 모드에 따라 다르므로 서비스에서 판단</li>
 * </ul>
 */
@Entity
@Table(name = "approvals")
public class Approval {

    private static final int MAX_COMMENT = 500;
    private static final int MAX_STEPS = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_type", nullable = false, length = 20, updatable = false)
    private ApprovalTarget targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private Long requestedBy;

    /** 최종 결정한 사람 (마지막 단계 승인자 또는 반려한 사람). 취소는 없음 */
    @Column(name = "approver_id")
    private Long approverId;

    /** 지금 결재할 단계 번호 */
    @Column(name = "current_step", nullable = false)
    private int currentStep = 1;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ApprovalStatus status = ApprovalStatus.PENDING;

    /** 반려 사유(필수) 또는 승인·취소 메모 */
    @Column(length = MAX_COMMENT)
    private String comment;

    @Column(name = "due_at", columnDefinition = "datetime(6)")
    private LocalDateTime dueAt;

    /** 요청 시각. 결정 시각과 비교하므로 앱 시각으로 넣는다 (V6 ck_appr_order) */
    @Column(name = "requested_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime requestedAt;

    @Column(name = "decided_at", columnDefinition = "datetime(6)")
    private LocalDateTime decidedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    protected Approval() {
    }

    public Approval(Long siteId, ApprovalTarget targetType, Long targetId, Long requestedBy,
                    LocalDateTime dueAt, LocalDateTime now) {
        if (siteId == null || targetType == null || targetId == null || requestedBy == null || now == null) {
            throw new IllegalArgumentException("현장·대상 기록·요청자·요청 시각이 필요합니다.");
        }
        if (dueAt != null && !dueAt.isAfter(now)) {
            throw new IllegalArgumentException("결재 기한은 요청 시각보다 뒤여야 합니다.");
        }
        this.siteId = siteId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.requestedBy = requestedBy;
        this.dueAt = dueAt;
        this.requestedAt = now;
    }

    /** 현재 단계 승인 후 다음 단계로 (마지막 단계면 approve) */
    public void moveTo(int nextStep) {
        requirePending();
        if (nextStep != currentStep + 1 || nextStep > MAX_STEPS) {
            throw new IllegalArgumentException("다음 단계는 " + (currentStep + 1) + " 이어야 합니다.");
        }
        this.currentStep = nextStep;
    }

    /** 마지막 단계 승인 = 결재 완료 */
    public void approve(Long userId, String comment, LocalDateTime now) {
        decide(ApprovalStatus.APPROVED, userId, optionalComment(comment), now);
    }

    /** 반려. 사유 필수 (V6 ck_appr_reason) */
    public void reject(Long userId, String reason, LocalDateTime now) {
        decide(ApprovalStatus.REJECTED, userId, requiredReason(reason), now);
    }

    /** 요청자가 회수. 결정한 사람 없음 */
    public void cancel(String comment, LocalDateTime now) {
        decide(ApprovalStatus.CANCELED, null, optionalComment(comment), now);
    }

    public boolean isPending() {
        return status == ApprovalStatus.PENDING;
    }

    /** 기한이 지났는데 아직 대기 중 (마감 알림용) */
    public boolean isOverdue(LocalDateTime now) {
        return isPending() && dueAt != null && now != null && now.isAfter(dueAt);
    }

    private void decide(ApprovalStatus result, Long userId, String comment, LocalDateTime now) {
        requirePending();
        if (result != ApprovalStatus.CANCELED && userId == null) {
            throw new IllegalArgumentException("결정한 사람이 필요합니다.");
        }
        if (now == null || now.isBefore(requestedAt)) {
            throw new IllegalArgumentException("결정 시각은 요청 시각 이후여야 합니다.");
        }
        this.status = result;
        this.approverId = userId;
        this.comment = comment;
        this.decidedAt = now;
    }

    private void requirePending() {
        if (status != ApprovalStatus.PENDING) {
            throw new IllegalStateException("이미 결정된 결재입니다 (현재 " + status + "). 새 결재를 요청하세요.");
        }
    }

    static String requiredReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("반려 사유가 필요합니다.");
        }
        return optionalComment(reason);
    }

    static String optionalComment(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String trimmed = comment.trim();
        if (trimmed.length() > MAX_COMMENT) {
            throw new IllegalArgumentException("의견은 " + MAX_COMMENT + "자 이내로 입력하세요.");
        }
        return trimmed;
    }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public ApprovalTarget getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public Long getRequestedBy() { return requestedBy; }
    public Long getApproverId() { return approverId; }
    public int getCurrentStep() { return currentStep; }
    public ApprovalStatus getStatus() { return status; }
    public String getComment() { return comment; }
    public LocalDateTime getDueAt() { return dueAt; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public int getLockVersion() { return lockVersion; }
}
