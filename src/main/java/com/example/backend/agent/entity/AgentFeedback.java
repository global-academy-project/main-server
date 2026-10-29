package com.example.backend.agent.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * AI 결과에 대한 사용자 평가 (3주차 튜닝 자료). 테이블 규칙은 V8 agent_feedback.
 * 한 실행에 한 사람 한 번 (바꾸면 change). 싫어요(DOWN)는 이유 필수.
 */
@Entity
@Table(name = "agent_feedback")
public class AgentFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private Long runId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 5)
    private FeedbackRating rating;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "reason_code", length = 20)
    private FeedbackReason reasonCode;

    @Column(length = 500)
    private String comment;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected AgentFeedback() {
    }

    public AgentFeedback(Long runId, Long userId, FeedbackRating rating, FeedbackReason reasonCode, String comment) {
        this.runId = runId;
        this.userId = userId;
        change(rating, reasonCode, comment);
    }

    public void change(FeedbackRating rating, FeedbackReason reasonCode, String comment) {
        if (rating == FeedbackRating.DOWN && reasonCode == null) {
            throw new IllegalArgumentException("싫어요는 이유가 필요합니다.");
        }
        this.rating = rating;
        this.reasonCode = reasonCode;
        this.comment = comment;
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public Long getUserId() { return userId; }
    public FeedbackRating getRating() { return rating; }
    public FeedbackReason getReasonCode() { return reasonCode; }
    public String getComment() { return comment; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
