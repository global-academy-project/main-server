package com.example.backend.job.entity;

import com.example.backend.common.util.JsonText;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * 시간이 걸리는 비동기 작업 (PDF 출력, 규칙 재판정, 감사 보고서, 안전일지 집계). AI 실행은 agent_runs.
 * 테이블 규칙은 V6 async_jobs. @Async 만 쓰면 재시작 때 사라지므로 행으로 남겨 이어서 처리·재시도한다.
 * <ul>
 *   <li>QUEUED → (start) RUNNING → DONE / 재시도면 다시 QUEUED / 횟수 초과면 FAILED. 언제든 CANCELED</li>
 *   <li>같은 idempotency_key 는 작업 하나 (V6 uk_job_idem). 예: EXPORT_PDF:PERMIT:5001:v7</li>
 *   <li>PDF·감사 보고서는 DONE 이면 결과 문서 필수 (V6 ck_job_pdf)</li>
 * </ul>
 */
@Entity
@Table(name = "async_jobs")
public class AsyncJob {

    /** 끝나면 결과 문서가 있어야 하는 작업 */
    private static final Set<JobType> PRODUCES_DOCUMENT = EnumSet.of(JobType.EXPORT_PDF, JobType.AUDIT_REPORT);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private Long requestedBy;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "job_type", nullable = false, length = 20, updatable = false)
    private JobType jobType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_type", nullable = false, length = 20, updatable = false)
    private JobTarget targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    /** 요청 시점 기록의 lock_version (처리 중 내용이 바뀌면 실패 처리 후 다시 요청) */
    @Column(name = "target_version", nullable = false, updatable = false)
    private int targetVersion;

    /** 예: {"formType":"CONFINED_SPACE_PERMIT"} */
    @Column(updatable = false, columnDefinition = "json")
    private String params;

    @Column(name = "idempotency_key", nullable = false, length = 120, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private JobStatus status = JobStatus.QUEUED;

    @Column(nullable = false, columnDefinition = "tinyint")
    private int progress;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false, updatable = false)
    private int maxAttempts;

    /** 이 시각 이후에 가져간다 (재시도 대기) */
    @Column(name = "run_after", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime runAfter;

    /** 처리 중인 서버 인스턴스 */
    @Column(name = "locked_by", length = 100)
    private String lockedBy;

    @Column(name = "result_document_id")
    private Long resultDocumentId;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "started_at", columnDefinition = "datetime(6)")
    private LocalDateTime startedAt;

    @Column(name = "finished_at", columnDefinition = "datetime(6)")
    private LocalDateTime finishedAt;

    protected AsyncJob() {
    }

    public AsyncJob(Long siteId, Long requestedBy, JobType jobType, JobTarget targetType, Long targetId,
                    int targetVersion, String paramsJson, String idempotencyKey, int maxAttempts, LocalDateTime now) {
        if (siteId == null || requestedBy == null || jobType == null || targetType == null || targetId == null
                || now == null) {
            throw new IllegalArgumentException("현장·요청자·작업 종류·대상 기록·시각이 필요합니다.");
        }
        if (targetVersion < 0) {
            throw new IllegalArgumentException("대상 판은 0 이상입니다.");
        }
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("최대 시도 횟수는 1~10 입니다.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 120) {
            throw new IllegalArgumentException("중복 방지 키가 필요합니다 (120자 이내).");
        }
        this.siteId = siteId;
        this.requestedBy = requestedBy;
        this.jobType = jobType;
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetVersion = targetVersion;
        this.params = JsonText.objectOrNull(paramsJson, "작업 인자");
        this.idempotencyKey = idempotencyKey;
        this.maxAttempts = maxAttempts;
        this.runAfter = now;
    }

    /** 작업자가 가져감. 시도 횟수 +1 */
    public void start(String workerName, LocalDateTime now) {
        requireStatus(JobStatus.QUEUED, "대기 중인 작업만 시작할 수 있습니다.");
        if (workerName == null || workerName.isBlank() || workerName.length() > 100 || now == null) {
            throw new IllegalArgumentException("처리 서버 이름(100자 이내)과 시각이 필요합니다.");
        }
        if (attempts >= maxAttempts) {
            throw new IllegalStateException("시도 횟수를 모두 썼습니다.");
        }
        this.status = JobStatus.RUNNING;
        this.attempts++;
        this.lockedBy = workerName;
        this.startedAt = now;
    }

    public void report(int percent) {
        requireStatus(JobStatus.RUNNING, "실행 중인 작업만 진행률을 바꿀 수 있습니다.");
        if (percent < 0 || percent > 99) {
            throw new IllegalArgumentException("진행률은 0~99 입니다 (100 은 완료 때).");
        }
        this.progress = Math.max(progress, percent);
    }

    /** 완료. PDF·감사 보고서는 결과 문서 필수 */
    public void succeed(Long documentId, LocalDateTime now) {
        requireStatus(JobStatus.RUNNING, "실행 중인 작업만 완료할 수 있습니다.");
        if (PRODUCES_DOCUMENT.contains(jobType) && documentId == null) {
            throw new IllegalArgumentException("출력 작업은 결과 문서가 필요합니다.");
        }
        this.status = JobStatus.DONE;
        this.progress = 100;
        this.resultDocumentId = documentId;
        this.errorMessage = null;
        this.finishedAt = notNull(now);
    }

    /**
     * 실패. 시도가 남았으면 retryDelay 뒤에 다시 대기(QUEUED), 다 썼으면 FAILED.
     * @return 다시 시도하면 true
     */
    public boolean fail(String error, Duration retryDelay, LocalDateTime now) {
        requireStatus(JobStatus.RUNNING, "실행 중인 작업만 실패 처리할 수 있습니다.");
        if (error == null || error.isBlank()) {
            throw new IllegalArgumentException("실패 사유가 필요합니다.");
        }
        notNull(now);
        this.errorMessage = error;
        this.lockedBy = null;
        if (attempts < maxAttempts) {
            this.status = JobStatus.QUEUED;
            this.runAfter = now.plus(retryDelay == null || retryDelay.isNegative() ? Duration.ZERO : retryDelay);
            return true;
        }
        this.status = JobStatus.FAILED;
        this.finishedAt = now;
        return false;
    }

    /** 취소 (대기·실행 중만) */
    public void cancel(LocalDateTime now) {
        if (isFinished()) {
            throw new IllegalStateException("이미 끝난 작업입니다 (현재 " + status + ").");
        }
        this.status = JobStatus.CANCELED;
        this.lockedBy = null;
        this.finishedAt = notNull(now);
    }

    public boolean isFinished() {
        return status == JobStatus.DONE || status == JobStatus.FAILED || status == JobStatus.CANCELED;
    }

    private void requireStatus(JobStatus expected, String message) {
        if (status != expected) {
            throw new IllegalStateException(message + " (현재 " + status + ")");
        }
    }

    private static LocalDateTime notNull(LocalDateTime now) {
        if (now == null) {
            throw new IllegalArgumentException("시각이 필요합니다.");
        }
        return now;
    }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public Long getRequestedBy() { return requestedBy; }
    public JobType getJobType() { return jobType; }
    public JobTarget getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public int getTargetVersion() { return targetVersion; }
    public String getParams() { return params; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public JobStatus getStatus() { return status; }
    public int getProgress() { return progress; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public LocalDateTime getRunAfter() { return runAfter; }
    public String getLockedBy() { return lockedBy; }
    public Long getResultDocumentId() { return resultDocumentId; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
}
