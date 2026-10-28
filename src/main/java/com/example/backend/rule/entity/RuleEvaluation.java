package com.example.backend.rule.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 판정 한 번의 기록. 테이블 규칙은 V7 rule_evaluations.
 * 감사 증거라 추가만 한다 (V9 트리거가 수정·삭제를 막는다).
 * input_snapshot 은 판정에 쓴 입력 그대로 → 나중에 "그때 무엇을 보고 막았나"를 다시 볼 수 있다.
 */
@Entity
@Table(name = "rule_evaluations")
public class RuleEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_type", nullable = false, updatable = false, length = 20)
    private EvaluationTarget targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 10)
    private EvaluationResult result;

    @Column(name = "input_snapshot", nullable = false, updatable = false, columnDefinition = "json")
    private String inputSnapshot;

    @Column(name = "rules_applied", nullable = false, updatable = false)
    private int rulesApplied;

    @Column(name = "agent_run_id", updatable = false)
    private Long agentRunId;

    @Column(name = "evaluated_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime evaluatedAt;

    protected RuleEvaluation() {
    }

    public RuleEvaluation(Long siteId, EvaluationTarget targetType, Long targetId, EvaluationResult result,
                          String inputSnapshot, int rulesApplied, Long agentRunId, LocalDateTime evaluatedAt) {
        this.siteId = siteId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.result = result;
        this.inputSnapshot = inputSnapshot;
        this.rulesApplied = rulesApplied;
        this.agentRunId = agentRunId;
        this.evaluatedAt = evaluatedAt;
    }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public EvaluationTarget getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public EvaluationResult getResult() { return result; }
    public String getInputSnapshot() { return inputSnapshot; }
    public int getRulesApplied() { return rulesApplied; }
    public Long getAgentRunId() { return agentRunId; }
    public LocalDateTime getEvaluatedAt() { return evaluatedAt; }
}
