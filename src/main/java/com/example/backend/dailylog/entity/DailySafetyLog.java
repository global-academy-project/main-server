package com.example.backend.dailylog.entity;

import com.example.backend.common.util.JsonText;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 일일 안전일지 (현장 × 날짜 1건). 테이블 규칙은 V6 daily_safety_logs.
 * <ul>
 *   <li>작성 주체 필수: 사람(writer_id) 또는 에이전트(agent_run_id) (V6 ck_dsl_author)</li>
 *   <li>DRAFT 를 벗어나면 요약 필수 (V6 ck_dsl_submit)</li>
 *   <li>APPROVED 후에는 내용·상태를 바꿀 수 없음 (V9 trg_dsl_lock)</li>
 * </ul>
 * 누가 승인했는지는 approvals(DAILY_LOG)에 남는다.
 */
@Entity
@Table(name = "daily_safety_logs")
public class DailySafetyLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Column(name = "log_date", nullable = false, updatable = false)
    private LocalDate logDate;

    @Column(columnDefinition = "text")
    private String summary;

    /** 집계 (JSON 문자열). 예: 작업 수, 차단 수, 아차사고 수 */
    @Column(columnDefinition = "json")
    private String stats;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private DailyLogStatus status = DailyLogStatus.DRAFT;

    @Column(name = "writer_id")
    private Long writerId;

    @Column(name = "agent_run_id", updatable = false)
    private Long agentRunId;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected DailySafetyLog() {
    }

    private DailySafetyLog(Long siteId, LocalDate logDate, Long writerId, Long agentRunId) {
        if (siteId == null || logDate == null) {
            throw new IllegalArgumentException("현장과 날짜가 필요합니다.");
        }
        if (writerId == null && agentRunId == null) {
            throw new IllegalArgumentException("작성자(사람 또는 에이전트 실행)가 필요합니다.");
        }
        this.siteId = siteId;
        this.logDate = logDate;
        this.writerId = writerId;
        this.agentRunId = agentRunId;
    }

    public static DailySafetyLog byUser(Long siteId, LocalDate logDate, Long writerId) {
        if (writerId == null) {
            throw new IllegalArgumentException("작성자가 필요합니다.");
        }
        return new DailySafetyLog(siteId, logDate, writerId, null);
    }

    /** 에이전트 초안 (집계 작업). 사람이 고치면 writer 가 붙는다 */
    public static DailySafetyLog byAgent(Long siteId, LocalDate logDate, Long agentRunId) {
        if (agentRunId == null) {
            throw new IllegalArgumentException("에이전트 실행 id 가 필요합니다.");
        }
        return new DailySafetyLog(siteId, logDate, null, agentRunId);
    }

    /** 내용 수정. 승인 전(DRAFT·PENDING)만. 고친 사람이 있으면 작성자로 기록 */
    public void update(String summary, String statsJson, Long editorId) {
        if (status == DailyLogStatus.APPROVED) {
            throw new IllegalStateException("승인된 안전일지는 고칠 수 없습니다.");
        }
        String text = summary == null || summary.isBlank() ? null : summary.trim();
        if (status != DailyLogStatus.DRAFT && text == null) {
            throw new IllegalArgumentException("제출된 안전일지는 요약을 비울 수 없습니다.");
        }
        this.summary = text;
        this.stats = JsonText.objectOrNull(statsJson, "집계");
        if (editorId != null) {
            this.writerId = editorId;
        }
    }

    /** 승인 요청. 요약 필수 */
    public void submit() {
        requireStatus(DailyLogStatus.DRAFT, "작성 중인 안전일지만 승인 요청할 수 있습니다.");
        if (summary == null) {
            throw new IllegalStateException("요약을 작성한 뒤 승인 요청하세요.");
        }
        this.status = DailyLogStatus.PENDING;
    }

    /** 승인 요청 취소·반려 → 다시 작성 중 */
    public void reopen() {
        requireStatus(DailyLogStatus.PENDING, "승인 대기 중인 안전일지만 되돌릴 수 있습니다.");
        this.status = DailyLogStatus.DRAFT;
    }

    public void approve() {
        requireStatus(DailyLogStatus.PENDING, "승인 대기 중인 안전일지만 승인할 수 있습니다.");
        this.status = DailyLogStatus.APPROVED;
    }

    private void requireStatus(DailyLogStatus expected, String message) {
        if (status != expected) {
            throw new IllegalStateException(message + " (현재 " + status + ")");
        }
    }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public LocalDate getLogDate() { return logDate; }
    public String getSummary() { return summary; }
    public String getStats() { return stats; }
    public DailyLogStatus getStatus() { return status; }
    public Long getWriterId() { return writerId; }
    public Long getAgentRunId() { return agentRunId; }
    public int getLockVersion() { return lockVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
