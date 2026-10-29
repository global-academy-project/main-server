package com.example.backend.agent.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 실행의 단계 기록. 테이블 규칙은 V8 agent_steps.
 * AI 서버가 단계를 모아 실행 끝에 한 번에 돌려주면 Spring 이 agent_runs 결과와 같은 트랜잭션에 저장한다
 * (도구 호출마다 따로 남기지 않는다). 도구 호출 결과 원본은 output_json 에.
 */
@Entity
@Table(name = "agent_steps")
public class AgentStep {

    /** 도구 이름 (DB ck_as_toolname, AI 서버 도구 레지스트리 이름과 같다) */
    public static final Set<String> TOOL_NAMES = Set.of(
            "search_accidents", "search_guides", "lookup_msds", "classify_work", "check_rules",
            "check_photo", "fill_form", "export_form", "search_records", "request_approval");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private Long runId;

    @Column(nullable = false, updatable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "step_type", nullable = false, updatable = false, length = 20)
    private AgentStepType stepType;

    @Column(name = "tool_name", updatable = false, length = 40)
    private String toolName;

    @Column(name = "input_json", updatable = false, columnDefinition = "json")
    private String inputJson;

    @Column(name = "output_json", columnDefinition = "json")
    private String outputJson;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private AgentStepStatus status = AgentStepStatus.DONE;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected AgentStep() {
    }

    /**
     * @param seq      실행 안에서 1부터
     * @param toolName TOOL_CALL 이면 필수(TOOL_NAMES 중 하나), 아니면 null
     */
    public AgentStep(Long runId, int seq, AgentStepType stepType, String toolName, String inputJson,
                     String outputJson, AgentStepStatus status, Integer latencyMs) {
        if (seq < 1) {
            throw new IllegalArgumentException("단계 순번은 1부터입니다.");
        }
        if ((stepType == AgentStepType.TOOL_CALL) != (toolName != null)) {
            throw new IllegalArgumentException("도구 이름은 TOOL_CALL 단계에만 있어야 합니다.");
        }
        if (toolName != null && !TOOL_NAMES.contains(toolName)) {
            throw new IllegalArgumentException("알 수 없는 도구 이름: " + toolName);
        }
        this.runId = runId;
        this.seq = seq;
        this.stepType = stepType;
        this.toolName = toolName;
        this.inputJson = inputJson;
        this.outputJson = outputJson;
        this.status = status == null ? AgentStepStatus.DONE : status;
        this.latencyMs = latencyMs;
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public int getSeq() { return seq; }
    public AgentStepType getStepType() { return stepType; }
    public String getToolName() { return toolName; }
    public String getInputJson() { return inputJson; }
    public String getOutputJson() { return outputJson; }
    public AgentStepStatus getStatus() { return status; }
    public Integer getLatencyMs() { return latencyMs; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
