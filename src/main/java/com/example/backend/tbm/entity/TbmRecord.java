package com.example.backend.tbm.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * TBM 1회 (한 팀이 모여 하는 작업 전 회의). 테이블 규칙은 V4 tbm_records.
 * 다룬 작업은 tbm_works, 참석자는 tbm_attendees, 서명은 signatures(V6).
 * 승인 후에는 고칠 수 없다 (V9 trg_tbm_lock). 날짜를 바꾸면 tbm_works.tbm_date 도 DB가 같이 바꾼다 (ON UPDATE CASCADE).
 */
@Entity
@Table(name = "tbm_records")
public class TbmRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Column(name = "team_id")
    private Long teamId;

    /** 실시 장소 (같은 현장 구역) */
    @Column(name = "zone_id")
    private Long zoneId;

    @Column(name = "tbm_date", nullable = false)
    private LocalDate tbmDate;

    @Column(name = "held_at", columnDefinition = "datetime(6)")
    private LocalDateTime heldAt;

    @Column(name = "leader_name", length = 50)
    private String leaderName;

    /** 현장 인원 수 (참석자 명단과 별개, 사진 인원 비교용) */
    @Column(name = "attendee_count")
    private Integer attendeeCount;

    /** 교육 내용 (JSON 배열 문자열). 예: ["화기작업 전 가연물 제거", "LEL 측정 확인"] */
    @Column(name = "education_points", columnDefinition = "json")
    private String educationPoints;

    @Column(name = "health_check", length = 500)
    private String healthCheck;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private TbmStatus status = TbmStatus.DRAFT;

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

    protected TbmRecord() {
    }

    public TbmRecord(Long siteId, LocalDate tbmDate, Long teamId, Long zoneId, Long writerId) {
        if (siteId == null || tbmDate == null || writerId == null) {
            throw new IllegalArgumentException("현장·날짜·작성자가 필요합니다.");
        }
        this.siteId = siteId;
        this.tbmDate = tbmDate;
        this.teamId = teamId;
        this.zoneId = zoneId;
        this.writerId = writerId;
    }

    private void requireEditable() {
        if (!status.isEditable()) {
            throw new IllegalStateException("승인된 TBM 기록은 고칠 수 없습니다.");
        }
    }

    public void updateContent(Long teamId, Long zoneId, LocalDate tbmDate, String leaderName,
                              String educationPoints, String healthCheck) {
        requireEditable();
        if (tbmDate == null) {
            throw new IllegalArgumentException("TBM 날짜가 필요합니다.");
        }
        this.teamId = teamId;
        this.zoneId = zoneId;
        this.tbmDate = tbmDate;
        this.leaderName = leaderName;
        this.educationPoints = educationPoints;
        this.healthCheck = healthCheck;
    }

    /** 실시 완료. 실시 시각·현장 인원 필수 (DB ck_tbm_done) */
    public void complete(LocalDateTime heldAt, int attendeeCount) {
        requireEditable();
        if (heldAt == null) {
            throw new IllegalArgumentException("실시 시각이 필요합니다.");
        }
        if (attendeeCount < 0) {
            throw new IllegalArgumentException("인원은 0 이상입니다.");
        }
        this.heldAt = heldAt;
        this.attendeeCount = attendeeCount;
        this.status = TbmStatus.DONE;
    }

    /** 실시 완료를 되돌려 다시 작성 (승인 전만) */
    public void reopen() {
        requireEditable();
        this.status = TbmStatus.DRAFT;
    }

    public void approve() {
        if (status != TbmStatus.DONE) {
            throw new IllegalStateException("실시 완료된 TBM만 승인할 수 있습니다 (현재 " + status + ").");
        }
        this.status = TbmStatus.APPROVED;
    }

    public void linkRun(Long agentRunId) { this.agentRunId = agentRunId; }

    public boolean isEditable() { return status.isEditable(); }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public Long getTeamId() { return teamId; }
    public Long getZoneId() { return zoneId; }
    public LocalDate getTbmDate() { return tbmDate; }
    public LocalDateTime getHeldAt() { return heldAt; }
    public String getLeaderName() { return leaderName; }
    public Integer getAttendeeCount() { return attendeeCount; }
    public String getEducationPoints() { return educationPoints; }
    public String getHealthCheck() { return healthCheck; }
    public TbmStatus getStatus() { return status; }
    public Long getWriterId() { return writerId; }
    public Long getAgentRunId() { return agentRunId; }
    public int getLockVersion() { return lockVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
