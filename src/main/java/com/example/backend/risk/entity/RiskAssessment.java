package com.example.backend.risk.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 위험성평가. 테이블 규칙은 V3 risk_assessments.
 * 일일 평가(DAILY)는 작업 단위, 최초·정기·수시는 구역(공정) 단위. 일일 평가는 정기·수시 평가를 참고할 수 있다(base).
 * DRAFT 에서만 내용을 고칠 수 있다 (V9 trg_risk_lock). 승인 흐름(approvals)은 담당자가 여기에 메서드를 더해 쓴다.
 */
@Entity
@Table(name = "risk_assessments")
public class RiskAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "assessment_type", nullable = false, length = 10, updatable = false)
    private AssessmentType assessmentType;

    /** 일일 평가의 작업 (같은 현장) */
    @Column(name = "work_id", updatable = false)
    private Long workId;

    /** 정기·수시 평가의 대상 구역 */
    @Column(name = "zone_id")
    private Long zoneId;

    /** 정기·수시 평가 이름 (예: 2026 반응공정 정기 위험성평가) */
    @Column(length = 200)
    private String title;

    /** 일일 평가가 참고한 정기·수시 평가 */
    @Column(name = "base_assessment_id")
    private Long baseAssessmentId;

    @Column(nullable = false, updatable = false)
    private int revision = 1;

    @Column(name = "assess_date", nullable = false)
    private LocalDate assessDate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private RiskMethod method = RiskMethod.FREQ_SEVERITY;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private RiskStatus status = RiskStatus.DRAFT;

    @Column(name = "form_schema_version", nullable = false, length = 20)
    private String formSchemaVersion = "v1";

    /** 양식 원본 값 (JSON 문자열) */
    @Column(name = "form_data", columnDefinition = "json")
    private String formData;

    @Column(name = "writer_id", nullable = false, updatable = false)
    private Long writerId;

    @Column(name = "agent_run_id")
    private Long agentRunId;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected RiskAssessment() {
    }

    private RiskAssessment(Long siteId, AssessmentType type, Long workId, Long zoneId, String title,
                           int revision, LocalDate assessDate, Long writerId) {
        if (siteId == null || type == null || assessDate == null || writerId == null) {
            throw new IllegalArgumentException("현장·종류·평가일·작성자가 필요합니다.");
        }
        if (type == AssessmentType.DAILY && workId == null) {
            throw new IllegalArgumentException("일일 위험성평가는 작업이 필요합니다.");
        }
        if (type != AssessmentType.DAILY && (title == null || title.isBlank())) {
            throw new IllegalArgumentException("최초·정기·수시 위험성평가는 이름이 필요합니다.");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("판(revision)은 1 이상입니다.");
        }
        this.siteId = siteId;
        this.assessmentType = type;
        this.workId = workId;
        this.zoneId = zoneId;
        this.title = title == null ? null : title.trim();
        this.revision = revision;
        this.assessDate = assessDate;
        this.writerId = writerId;
    }

    /** 일일 위험성평가 (작업 단위) */
    public static RiskAssessment daily(Long siteId, Long workId, LocalDate assessDate, Long writerId) {
        return new RiskAssessment(siteId, AssessmentType.DAILY, workId, null, null, 1, assessDate, writerId);
    }

    /** 최초·정기·수시 위험성평가 (구역 단위) */
    public static RiskAssessment periodic(Long siteId, AssessmentType type, Long zoneId, String title,
                                          LocalDate assessDate, Long writerId) {
        if (type == AssessmentType.DAILY) {
            throw new IllegalArgumentException("일일 평가는 daily(...)로 만듭니다.");
        }
        return new RiskAssessment(siteId, type, null, zoneId, title, 1, assessDate, writerId);
    }

    /**
     * 반려된 평가를 다시 쓸 새 판. 내용을 그대로 복사하고 revision + 1, DRAFT.
     * 이 행은 supersede() 로 SUPERSEDED 처리한다. 위험요인(items)은 서비스에서 복사.
     */
    public RiskAssessment newRevision(Long writerId) {
        RiskAssessment next = new RiskAssessment(siteId, assessmentType, workId, zoneId, title,
                revision + 1, assessDate, writerId);
        next.baseAssessmentId = baseAssessmentId;
        next.method = method;
        next.formSchemaVersion = formSchemaVersion;
        next.formData = formData;
        return next;
    }

    private void requireDraft() {
        if (!status.isEditable()) {
            throw new IllegalStateException("제출된 위험성평가는 고칠 수 없습니다 (현재 " + status + ").");
        }
    }

    /** 내용 수정 (DRAFT 만) */
    public void updateContent(Long zoneId, String title, LocalDate assessDate, RiskMethod method,
                              String formSchemaVersion, String formData) {
        requireDraft();
        if (assessmentType != AssessmentType.DAILY && (title == null || title.isBlank())) {
            throw new IllegalArgumentException("최초·정기·수시 위험성평가는 이름이 필요합니다.");
        }
        this.zoneId = zoneId;
        this.title = title == null ? null : title.trim();
        this.assessDate = assessDate;
        this.method = method == null ? RiskMethod.FREQ_SEVERITY : method;
        this.formSchemaVersion = formSchemaVersion == null ? "v1" : formSchemaVersion;
        this.formData = formData;
    }

    /** 일일 평가가 참고한 정기·수시 평가 (DB ck_risk_base: 일일 평가만) */
    public void referBase(Long baseAssessmentId) {
        requireDraft();
        if (baseAssessmentId != null && assessmentType != AssessmentType.DAILY) {
            throw new IllegalArgumentException("기존 평가 참고는 일일 평가만 가능합니다.");
        }
        this.baseAssessmentId = baseAssessmentId;
    }

    public void linkRun(Long agentRunId) { this.agentRunId = agentRunId; }

    /** 승인 요청 */
    public void submit() {
        requireDraft();
        this.status = RiskStatus.PENDING;
    }

    /** 승인 요청 취소 → 다시 수정 가능 */
    public void withdraw() {
        requireStatus(RiskStatus.PENDING);
        this.status = RiskStatus.DRAFT;
    }

    public void approve() {
        requireStatus(RiskStatus.PENDING);
        this.status = RiskStatus.APPROVED;
    }

    public void reject() {
        requireStatus(RiskStatus.PENDING);
        this.status = RiskStatus.REJECTED;
    }

    /** 새 판이 생겨 대체됨 */
    public void supersede() {
        this.status = RiskStatus.SUPERSEDED;
    }

    private void requireStatus(RiskStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("상태가 " + expected + " 일 때만 할 수 있습니다 (현재 " + status + ").");
        }
    }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public AssessmentType getAssessmentType() { return assessmentType; }
    public Long getWorkId() { return workId; }
    public Long getZoneId() { return zoneId; }
    public String getTitle() { return title; }
    public Long getBaseAssessmentId() { return baseAssessmentId; }
    public int getRevision() { return revision; }
    public LocalDate getAssessDate() { return assessDate; }
    public RiskMethod getMethod() { return method; }
    public RiskStatus getStatus() { return status; }
    public String getFormSchemaVersion() { return formSchemaVersion; }
    public String getFormData() { return formData; }
    public Long getWriterId() { return writerId; }
    public Long getAgentRunId() { return agentRunId; }
    public int getLockVersion() { return lockVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
