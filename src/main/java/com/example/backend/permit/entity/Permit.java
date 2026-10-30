package com.example.backend.permit.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 작업허가서 한 판(revision). 테이블 규칙은 V4 permits. 테이블 전체를 매핑해 두고 기능은 담당자가 메서드로 더한다.
 * 승인 요청(PENDING) 뒤에는 내용을 고칠 수 없다 (V9 trg_permits_lock). 바꾸려면 새 판을 만든다 (예정).
 * form_data: 양식 입력값 JSON (예: {"extinguisher_ready": true, "spark_prevention": true})
 */
@Entity
@Table(name = "permits")
public class Permit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Column(name = "work_id", nullable = false, updatable = false)
    private Long workId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "permit_type", nullable = false, updatable = false, length = 20)
    private PermitType permitType;

    @Column(nullable = false, updatable = false)
    private int revision = 1;

    @Column(name = "permit_no", nullable = false, updatable = false, length = 40)
    private String permitNo;

    @Column(name = "valid_from", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime validFrom;

    @Column(name = "valid_to", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime validTo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private PermitStatus status = PermitStatus.DRAFT;

    /** 화재감시자(화기) / 감시인(밀폐) */
    @Column(name = "watcher_name", length = 50)
    private String watcherName;

    @Column(name = "supervisor_name", length = 50)
    private String supervisorName;

    @Column(name = "form_schema_version", nullable = false, length = 20)
    private String formSchemaVersion = "v1";

    @Column(name = "form_data", columnDefinition = "json")
    private String formData;

    /** 최근 판정. 내용·측정값이 바뀌면 비운다 (다시 판정해야 승인 요청 가능) */
    @Column(name = "last_evaluation_id")
    private Long lastEvaluationId;

    @Column(name = "writer_id", nullable = false, updatable = false)
    private Long writerId;

    /** 에이전트가 초안을 만든 허가면 그 실행 */
    @Column(name = "agent_run_id")
    private Long agentRunId;

    /** 완료 시각 (status = CLOSED 와 항상 같이, DB ck_permits_closed) */
    @Column(name = "closed_at", columnDefinition = "datetime(6)")
    private LocalDateTime closedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected Permit() {
    }

    public Permit(Long siteId, Long workId, PermitType permitType, String permitNo, LocalDateTime validFrom,
                  LocalDateTime validTo, String watcherName, String supervisorName, String formData, Long writerId) {
        this.siteId = siteId;
        this.workId = workId;
        this.permitType = permitType;
        this.permitNo = permitNo;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.watcherName = watcherName;
        this.supervisorName = supervisorName;
        this.formData = formData;
        this.writerId = writerId;
    }

    /** 내용 수정. 판정은 다시 해야 한다 */
    public void updateContent(LocalDateTime validFrom, LocalDateTime validTo, String watcherName,
                              String supervisorName, String formData) {
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.watcherName = watcherName;
        this.supervisorName = supervisorName;
        this.formData = formData;
        invalidateEvaluation();
    }

    /** 내용이나 측정값이 바뀜 → 최근 판정 무효, 작성 중으로 */
    public void invalidateEvaluation() {
        this.lastEvaluationId = null;
        this.status = PermitStatus.DRAFT;
    }

    /** 판정 결과 반영: BLOCK 이면 BLOCKED, 아니면 DRAFT (승인 요청 가능) */
    public void applyEvaluation(Long evaluationId, boolean blocked) {
        this.lastEvaluationId = evaluationId;
        this.status = blocked ? PermitStatus.BLOCKED : PermitStatus.DRAFT;
    }

    public void submit() {
        this.status = PermitStatus.PENDING;
    }

    /** 결재 승인 (마지막 단계까지) */
    public void approve() {
        requirePending();
        this.status = PermitStatus.APPROVED;
    }

    /** 결재 반려. 다시 쓰려면 새 판(revision)을 만든다 (DB 설계: 반려된 판은 그대로 보존) */
    public void reject() {
        requirePending();
        this.status = PermitStatus.REJECTED;
    }

    /** 요청자가 결재를 회수 → 다시 작성 중. 내용은 그대로라 최근 판정도 그대로 유효 */
    public void withdraw() {
        requirePending();
        this.status = PermitStatus.DRAFT;
    }

    private void requirePending() {
        if (status != PermitStatus.PENDING) {
            throw new IllegalStateException("승인 요청된 허가서가 아닙니다 (현재 " + status + ").");
        }
    }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public Long getWorkId() { return workId; }
    public PermitType getPermitType() { return permitType; }
    public int getRevision() { return revision; }
    public String getPermitNo() { return permitNo; }
    public LocalDateTime getValidFrom() { return validFrom; }
    public LocalDateTime getValidTo() { return validTo; }
    public PermitStatus getStatus() { return status; }
    public String getWatcherName() { return watcherName; }
    public String getSupervisorName() { return supervisorName; }
    public String getFormData() { return formData; }
    public Long getLastEvaluationId() { return lastEvaluationId; }
    public Long getWriterId() { return writerId; }
    /** 에이전트가 만든 허가서면 실행 id */
    public void linkRun(Long agentRunId) {
        this.agentRunId = agentRunId;
    }

    public Long getAgentRunId() { return agentRunId; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public int getLockVersion() { return lockVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
