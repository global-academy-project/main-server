package com.example.backend.agent.entity;

import com.example.backend.common.type.NetworkMode;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 에이전트 실행 한 번 (사용자 메시지 하나 = 실행 하나). 테이블 규칙은 V8 agent_runs.
 *  - 기존 ocr_runs 처럼: 실행 저장(PROCESSING) → 커밋 후 비동기로 AI 서버 호출 → 결과로 DONE / FAILED / WAITING_INPUT
 *  - check_rules 가 판정을 저장할 때 이 실행 id 를 외래키로 참조하므로, AI 서버 호출 전에 커밋돼 있어야 한다
 *  - 끝난 실행(DONE·FAILED)은 finished_at 필수, FAILED 는 error_message 필수 (DB CHECK)
 */
@Entity
@Table(name = "agent_runs")
public class AgentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** 자동 실행(아차사고 태깅, 일지 생성 등)은 null */
    @Column(name = "conversation_id", updatable = false)
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "task_type", nullable = false, updatable = false, length = 30)
    private AgentTaskType taskType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 10)
    private AgentMode mode;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "network_mode", nullable = false, length = 10)
    private NetworkMode networkMode;

    /** 실제로 쓴 LLM 모델 이름 (AI 서버 응답) */
    @Column(length = 60)
    private String model;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private AgentRunStatus status = AgentRunStatus.PROCESSING;

    @Column(name = "input_json", updatable = false, columnDefinition = "json")
    private String inputJson;

    @Column(name = "output_json", columnDefinition = "json")
    private String outputJson;

    /** 이 실행이 만든·다룬 업무 기록 (예: PERMIT, 12) */
    @Column(name = "target_type", length = 20)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "total_ms")
    private Integer totalMs;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "finished_at", columnDefinition = "datetime(6)")
    private LocalDateTime finishedAt;

    protected AgentRun() {
    }

    public AgentRun(Long siteId, Long userId, Long conversationId, AgentTaskType taskType, AgentMode mode,
                    NetworkMode networkMode, String inputJson) {
        this.siteId = siteId;
        this.userId = userId;
        this.conversationId = conversationId;
        this.taskType = taskType;
        this.mode = mode;
        this.networkMode = networkMode;
        this.inputJson = inputJson;
    }

    /** 실행이 다룬 업무 기록 연결 (type 과 id 는 함께) */
    public void linkTarget(String targetType, Long targetId) {
        if ((targetType == null) != (targetId == null)) {
            throw new IllegalArgumentException("targetType 과 targetId 는 함께 있어야 합니다.");
        }
        this.targetType = targetType;
        this.targetId = targetId;
    }

    /** 성공 */
    public void complete(String outputJson, String model, NetworkMode networkMode, Integer totalMs,
                         Integer inputTokens, Integer outputTokens, LocalDateTime now) {
        requireNotFinished();
        applyUsage(model, networkMode, totalMs, inputTokens, outputTokens);
        this.outputJson = outputJson;
        this.status = AgentRunStatus.DONE;
        this.finishedAt = now;
    }

    /** 되묻기: 사용자 답을 기다림. 끝난 게 아니므로 finished_at 은 비운다 (답변은 다음 실행) */
    public void waitForInput(String outputJson, String model, NetworkMode networkMode, Integer totalMs,
                             Integer inputTokens, Integer outputTokens) {
        requireNotFinished();
        applyUsage(model, networkMode, totalMs, inputTokens, outputTokens);
        this.outputJson = outputJson;
        this.status = AgentRunStatus.WAITING_INPUT;
        this.finishedAt = null;
    }

    /** 실패. 오류 내용 필수 */
    public void fail(String errorMessage, Integer totalMs, LocalDateTime now) {
        if (errorMessage == null || errorMessage.isBlank()) {
            throw new IllegalArgumentException("실패한 실행은 오류 내용이 필요합니다.");
        }
        requireNotFinished();
        this.errorMessage = errorMessage;
        this.totalMs = totalMs;
        this.status = AgentRunStatus.FAILED;
        this.finishedAt = now;
    }

    public boolean isFinished() {
        return status == AgentRunStatus.DONE || status == AgentRunStatus.FAILED;
    }

    /** 끝난(DONE·FAILED) 실행은 결과를 다시 쓰지 않는다. 늦게 온 응답과 시간 초과 정리가 서로 덮어쓰지 않게 (저장 쪽도 행 잠금 후 확인) */
    private void requireNotFinished() {
        if (isFinished()) {
            throw new IllegalStateException("이미 끝난 실행입니다 (현재 " + status + ").");
        }
    }

    private void applyUsage(String model, NetworkMode networkMode, Integer totalMs, Integer inputTokens, Integer outputTokens) {
        this.model = model;
        if (networkMode != null) {
            this.networkMode = networkMode;
        }
        this.totalMs = totalMs;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public Long getUserId() { return userId; }
    public Long getConversationId() { return conversationId; }
    public AgentTaskType getTaskType() { return taskType; }
    public AgentMode getMode() { return mode; }
    public NetworkMode getNetworkMode() { return networkMode; }
    public String getModel() { return model; }
    public AgentRunStatus getStatus() { return status; }
    public String getInputJson() { return inputJson; }
    public String getOutputJson() { return outputJson; }
    public String getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public Integer getTotalMs() { return totalMs; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
}
