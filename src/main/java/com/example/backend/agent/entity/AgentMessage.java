package com.example.backend.agent.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 대화 메시지 (추가만). 테이블 규칙은 V8 agent_messages.
 *  - seq 는 AgentConversation.nextSeq() 로 받는다
 *  - 도구 결과 원본은 agent_steps 에, 여기에는 요약만
 *  - 본문(content) 또는 구조화 본문(content_json) 중 하나는 있어야 한다
 */
@Entity
@Table(name = "agent_messages")
public class AgentMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private Long conversationId;

    @Column(nullable = false, updatable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 10)
    private MessageRole role;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(updatable = false, columnDefinition = "mediumtext")
    private String content;

    @Column(name = "content_json", updatable = false, columnDefinition = "json")
    private String contentJson;

    @Column(name = "tool_name", updatable = false, length = 40)
    private String toolName;

    @Column(name = "tool_call_id", updatable = false, length = 64)
    private String toolCallId;

    /** 이 메시지를 만든(또는 이 메시지로 시작한) 실행 */
    @Column(name = "run_id")
    private Long runId;

    @Column(name = "token_count")
    private Integer tokenCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected AgentMessage() {
    }

    private AgentMessage(Long conversationId, int seq, MessageRole role, String content, String contentJson,
                         String toolName, String toolCallId, Long runId) {
        if (content == null && contentJson == null) {
            throw new IllegalArgumentException("메시지 본문이 비어 있습니다.");
        }
        if (role == MessageRole.TOOL && (toolName == null || toolCallId == null)) {
            throw new IllegalArgumentException("도구 메시지는 toolName 과 toolCallId 가 필요합니다.");
        }
        this.conversationId = conversationId;
        this.seq = seq;
        this.role = role;
        this.content = content;
        this.contentJson = contentJson;
        this.toolName = toolName;
        this.toolCallId = toolCallId;
        this.runId = runId;
    }

    public static AgentMessage user(Long conversationId, int seq, String content, Long runId) {
        return new AgentMessage(conversationId, seq, MessageRole.USER, content, null, null, null, runId);
    }

    public static AgentMessage assistant(Long conversationId, int seq, String content, String contentJson, Long runId) {
        return new AgentMessage(conversationId, seq, MessageRole.ASSISTANT, content, contentJson, null, null, runId);
    }

    /** 도구 호출 요약 (원본은 agent_steps) */
    public static AgentMessage tool(Long conversationId, int seq, String toolName, String toolCallId,
                                    String contentJson, Long runId) {
        return new AgentMessage(conversationId, seq, MessageRole.TOOL, null, contentJson, toolName, toolCallId, runId);
    }

    public void linkRun(Long runId) { this.runId = runId; }

    public void recordTokens(int tokenCount) { this.tokenCount = tokenCount; }

    public Long getId() { return id; }
    public Long getConversationId() { return conversationId; }
    public int getSeq() { return seq; }
    public MessageRole getRole() { return role; }
    public String getContent() { return content; }
    public String getContentJson() { return contentJson; }
    public String getToolName() { return toolName; }
    public String getToolCallId() { return toolCallId; }
    public Long getRunId() { return runId; }
    public Integer getTokenCount() { return tokenCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
