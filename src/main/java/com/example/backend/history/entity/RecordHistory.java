package com.example.backend.history.entity;

import com.example.backend.common.util.JsonText;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 작성·승인 이력 (감사 대응). 테이블 규칙은 V6 record_history.
 * 추가만 가능하다: 수정·삭제는 V9 trg_hist_* 가 막는다. 그래서 모든 컬럼이 updatable = false 이고 변경 메서드가 없다.
 * 행위 주체: USER = users.id, AGENT = agent_runs.id, SYSTEM = id 없음.
 */
@Entity
@Table(name = "record_history")
public class RecordHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 현장에 속하지 않는 작업(현장 생성, 관리자 계정 변경 등)은 null */
    @Column(name = "site_id", updatable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_type", nullable = false, length = 20, updatable = false)
    private HistoryTarget targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20, updatable = false)
    private HistoryAction action;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "actor_type", nullable = false, length = 10, updatable = false)
    private ActorType actorType;

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    /** 변경 전 내용 (JSON 문자열). 생성이면 null */
    @Column(name = "before_json", updatable = false, columnDefinition = "json")
    private String beforeJson;

    /** 변경 후 내용 (JSON 문자열) */
    @Column(name = "after_json", updatable = false, columnDefinition = "json")
    private String afterJson;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected RecordHistory() {
    }

    private RecordHistory(Long siteId, HistoryTarget targetType, Long targetId, HistoryAction action,
                          ActorType actorType, Long actorId, String beforeJson, String afterJson) {
        if (targetType == null || targetId == null || action == null || actorType == null) {
            throw new IllegalArgumentException("대상 기록·행위·행위 주체가 필요합니다.");
        }
        if (actorType != ActorType.SYSTEM && actorId == null) {
            throw new IllegalArgumentException("사용자·에이전트 이력은 행위자 id 가 필요합니다.");
        }
        this.siteId = siteId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.action = action;
        this.actorType = actorType;
        this.actorId = actorType == ActorType.SYSTEM ? null : actorId;
        this.beforeJson = JsonText.objectOrNull(beforeJson, "변경 전 내용");
        this.afterJson = JsonText.objectOrNull(afterJson, "변경 후 내용");
    }

    /** 사람이 한 일 (actorId = users.id) */
    public static RecordHistory byUser(Long siteId, HistoryTarget target, Long targetId, HistoryAction action,
                                       Long userId, String beforeJson, String afterJson) {
        return new RecordHistory(siteId, target, targetId, action, ActorType.USER, userId, beforeJson, afterJson);
    }

    /** 에이전트가 한 일, 예: AI_DRAFT (actorId = agent_runs.id) */
    public static RecordHistory byAgent(Long siteId, HistoryTarget target, Long targetId, HistoryAction action,
                                        Long agentRunId, String beforeJson, String afterJson) {
        return new RecordHistory(siteId, target, targetId, action, ActorType.AGENT, agentRunId, beforeJson, afterJson);
    }

    /** 배치·스케줄러가 한 일 (행위자 id 없음) */
    public static RecordHistory bySystem(Long siteId, HistoryTarget target, Long targetId, HistoryAction action,
                                         String beforeJson, String afterJson) {
        return new RecordHistory(siteId, target, targetId, action, ActorType.SYSTEM, null, beforeJson, afterJson);
    }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public HistoryTarget getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public HistoryAction getAction() { return action; }
    public ActorType getActorType() { return actorType; }
    public Long getActorId() { return actorId; }
    public String getBeforeJson() { return beforeJson; }
    public String getAfterJson() { return afterJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
