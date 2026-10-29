package com.example.backend.agent.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 에이전트 대화 (최근 항목 목록 한 줄). 테이블 규칙은 V8 agent_conversations.
 *  - 메시지를 추가할 때는 이 행을 잠그고(AgentConversationRepository.findForUpdate) nextSeq() 로 순번을 받는다
 *  - 지우기는 소프트 삭제(deleted_at)
 */
@Entity
@Table(name = "agent_conversations")
public class AgentConversation {

    private static final int TITLE_MAX = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** 최근 항목에 보이는 이름 (기본: 첫 사용자 메시지 앞부분) */
    @Column(nullable = false, length = TITLE_MAX)
    private String title;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "context_type", length = 20)
    private ConversationContext contextType;

    @Column(name = "context_id")
    private Long contextId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ConversationStatus status = ConversationStatus.ACTIVE;

    @Column(nullable = false)
    private boolean pinned;

    /** 긴 대화 요약 (summary_until_seq 번 메시지까지) */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "summary_until_seq")
    private Integer summaryUntilSeq;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "last_message_at", columnDefinition = "datetime(6)")
    private LocalDateTime lastMessageAt;

    @Column(name = "deleted_at", columnDefinition = "datetime(6)")
    private LocalDateTime deletedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected AgentConversation() {
    }

    public AgentConversation(Long siteId, Long userId, String title, ConversationContext contextType, Long contextId) {
        if ((contextType == null) != (contextId == null)) {
            throw new IllegalArgumentException("contextType 과 contextId 는 함께 있어야 합니다.");
        }
        this.siteId = siteId;
        this.userId = userId;
        this.title = titleOf(title);
        this.contextType = contextType;
        this.contextId = contextId;
    }

    /** 첫 사용자 메시지로 이름 만들기: 앞뒤 공백 정리, 100자까지 */
    public static String titleOf(String text) {
        String t = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            throw new IllegalArgumentException("대화 이름이 비어 있습니다.");
        }
        return t.length() <= TITLE_MAX ? t : t.substring(0, TITLE_MAX);
    }

    /** 메시지 하나를 추가할 순번. 이 행을 잠근 상태에서 불러야 동시 전송에도 순번이 꼬이지 않는다 */
    public int nextSeq(LocalDateTime now) {
        this.messageCount += 1;
        this.lastMessageAt = now;
        return messageCount;
    }

    public void rename(String title) { this.title = titleOf(title); }

    public void pin(boolean pinned) { this.pinned = pinned; }

    public void archive() { this.status = ConversationStatus.ARCHIVED; }

    public void softDelete(LocalDateTime now) { this.deletedAt = now; }

    /** 요약 갱신. untilSeq 는 1 ~ 지금 메시지 수 */
    public void updateSummary(String summary, int untilSeq) {
        if (summary == null || untilSeq < 1 || untilSeq > messageCount) {
            throw new IllegalArgumentException("요약 범위가 올바르지 않습니다.");
        }
        this.summary = summary;
        this.summaryUntilSeq = untilSeq;
    }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public Long getUserId() { return userId; }
    public String getTitle() { return title; }
    public ConversationContext getContextType() { return contextType; }
    public Long getContextId() { return contextId; }
    public ConversationStatus getStatus() { return status; }
    public boolean isPinned() { return pinned; }
    public String getSummary() { return summary; }
    public Integer getSummaryUntilSeq() { return summaryUntilSeq; }
    public int getMessageCount() { return messageCount; }
    public LocalDateTime getLastMessageAt() { return lastMessageAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public int getLockVersion() { return lockVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
