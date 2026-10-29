package com.example.backend.corrective.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 개선 조치 (아차사고·점검 불량·규칙 위반·감사 지적 이후 조치의 담당·기한·완료·확인). 테이블 규칙은 V5 corrective_actions.
 * 출처(source_type, source_id)는 다형 참조라 DB 외래키가 없다 → 서비스에서 존재 확인.
 * 조치자 = 확인자 금지 여부는 현장 모드에 따라 다르다 (안내 모드는 1인 업체 허용) → 서비스에서 판단.
 */
@Entity
@Table(name = "corrective_actions")
public class CorrectiveAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source_type", nullable = false, length = 20, updatable = false)
    private ActionSource sourceType;

    @Column(name = "source_id", updatable = false)
    private Long sourceId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "assignee_id", nullable = false)
    private Long assigneeId;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ActionStatus status = ActionStatus.OPEN;

    @Column(name = "completed_at", columnDefinition = "datetime(6)")
    private LocalDateTime completedAt;

    @Column(name = "completion_note", columnDefinition = "text")
    private String completionNote;

    @Column(name = "verified_by")
    private Long verifiedBy;

    @Column(name = "verified_at", columnDefinition = "datetime(6)")
    private LocalDateTime verifiedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected CorrectiveAction() {
    }

    public CorrectiveAction(Long siteId, ActionSource sourceType, Long sourceId, String title, String description,
                            Long assigneeId, LocalDate dueDate, Long createdBy) {
        if (siteId == null || sourceType == null || createdBy == null) {
            throw new IllegalArgumentException("현장·출처·등록자가 필요합니다.");
        }
        if (sourceType != ActionSource.OTHER && sourceId == null) {
            throw new IllegalArgumentException("출처 기록이 필요합니다 (기타 제외).");
        }
        this.siteId = siteId;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.createdBy = createdBy;
        update(title, description, assigneeId, dueDate);
    }

    /** 내용·담당·기한 수정 (끝나지 않은 조치만) */
    public void update(String title, String description, Long assigneeId, LocalDate dueDate) {
        if (status != null && !status.isOpen()) {
            throw new IllegalStateException("끝난 조치는 고칠 수 없습니다 (현재 " + status + ").");
        }
        if (title == null || title.isBlank() || assigneeId == null || dueDate == null) {
            throw new IllegalArgumentException("제목·담당자·기한이 필요합니다.");
        }
        this.title = title.trim();
        this.description = description;
        this.assigneeId = assigneeId;
        this.dueDate = dueDate;
    }

    public void start() {
        requireStatus(ActionStatus.OPEN);
        this.status = ActionStatus.IN_PROGRESS;
    }

    /** 완료. 완료 시각·내용 필수 (DB ck_ca_done) */
    public void complete(LocalDateTime now, String note) {
        if (!status.isOpen()) {
            throw new IllegalStateException("진행 중인 조치만 완료할 수 있습니다 (현재 " + status + ").");
        }
        if (now == null || note == null || note.isBlank()) {
            throw new IllegalArgumentException("완료 시각과 조치 내용이 필요합니다.");
        }
        this.completedAt = now;
        this.completionNote = note.trim();
        this.status = ActionStatus.DONE;
    }

    /** 확인. 확인자·시각 필수 (DB ck_ca_verified) */
    public void verify(Long userId, LocalDateTime now) {
        requireStatus(ActionStatus.DONE);
        if (userId == null || now == null) {
            throw new IllegalArgumentException("확인자와 시각이 필요합니다.");
        }
        this.verifiedBy = userId;
        this.verifiedAt = now;
        this.status = ActionStatus.VERIFIED;
    }

    /** 확인 단계에서 미흡 → 다시 조치 중 (완료 기록은 지움) */
    public void reopen() {
        requireStatus(ActionStatus.DONE);
        this.completedAt = null;
        this.completionNote = null;
        this.status = ActionStatus.IN_PROGRESS;
    }

    public void cancel() {
        if (!status.isOpen()) {
            throw new IllegalStateException("끝난 조치는 취소할 수 없습니다 (현재 " + status + ").");
        }
        this.status = ActionStatus.CANCELED;
    }

    /** 기한이 지났는데 끝나지 않았나 (기한 당일까지는 아님) */
    public boolean isOverdue(LocalDate today) {
        return status.isOpen() && dueDate.isBefore(today);
    }

    private void requireStatus(ActionStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("상태가 " + expected + " 일 때만 할 수 있습니다 (현재 " + status + ").");
        }
    }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public ActionSource getSourceType() { return sourceType; }
    public Long getSourceId() { return sourceId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Long getAssigneeId() { return assigneeId; }
    public LocalDate getDueDate() { return dueDate; }
    public ActionStatus getStatus() { return status; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public String getCompletionNote() { return completionNote; }
    public Long getVerifiedBy() { return verifiedBy; }
    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public Long getCreatedBy() { return createdBy; }
    public int getLockVersion() { return lockVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
