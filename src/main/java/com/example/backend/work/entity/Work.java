package com.example.backend.work.entity;

import com.example.backend.rule.entity.WorkType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 당일 작업. 테이블 규칙은 V3 works. 엔티티는 테이블 전체를 매핑해 두고, 각 기능(작업 등록 대화, 승인 흐름 등)은
 * 담당자가 여기에 메서드를 더해 쓴다. 승인·진행·완료·취소 후에는 내용을 고칠 수 없다 (V9 trg_works_lock).
 */
@Entity
@Table(name = "works")
public class Work {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    /** 작업구역 (work_zones, 같은 현장) */
    @Column(name = "zone_id")
    private Long zoneId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "work_type", nullable = false, length = 20)
    private WorkType workType;

    /** 화기·밀폐·고소면 TRUE (DB ck_works_highrisk) */
    @Column(name = "is_high_risk", nullable = false)
    private boolean highRisk;

    @Column(name = "start_at", columnDefinition = "datetime(6)")
    private LocalDateTime startAt;

    @Column(name = "end_at", columnDefinition = "datetime(6)")
    private LocalDateTime endAt;

    @Column(name = "worker_count")
    private Integer workerCount;

    /** 작업 소속 팀 (teams) */
    @Column(name = "team_id")
    private Long teamId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private WorkStatus status = WorkStatus.DRAFT;

    @Column(name = "writer_id", nullable = false, updatable = false)
    private Long writerId;

    /** 에이전트가 만든 작업이면 그 실행 */
    @Column(name = "agent_run_id")
    private Long agentRunId;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected Work() {
    }

    public Work(Long siteId, LocalDate workDate, String title, String description, WorkType workType,
                LocalDateTime startAt, LocalDateTime endAt, Integer workerCount, Long writerId) {
        this.siteId = siteId;
        this.workDate = workDate;
        this.title = title;
        this.description = description;
        this.workType = workType;
        this.highRisk = workType != WorkType.GENERAL;
        this.startAt = startAt;
        this.endAt = endAt;
        this.workerCount = workerCount;
        this.writerId = writerId;
    }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public Long getZoneId() { return zoneId; }
    public LocalDate getWorkDate() { return workDate; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public WorkType getWorkType() { return workType; }
    public boolean isHighRisk() { return highRisk; }
    public LocalDateTime getStartAt() { return startAt; }
    public LocalDateTime getEndAt() { return endAt; }
    public Integer getWorkerCount() { return workerCount; }
    public Long getTeamId() { return teamId; }
    public WorkStatus getStatus() { return status; }
    public Long getWriterId() { return writerId; }
    public Long getAgentRunId() { return agentRunId; }
    public int getLockVersion() { return lockVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
