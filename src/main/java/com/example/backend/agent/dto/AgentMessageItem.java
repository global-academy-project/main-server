package com.example.backend.agent.dto;

import com.example.backend.agent.entity.MessageRole;

import java.time.LocalDateTime;

/** GET /api/v1/agent/conversations/{id}/messages 의 메시지 한 줄 */
public record AgentMessageItem(Long messageId, int seq, MessageRole role, String content, Long runId,
                               LocalDateTime createdAt) {
}
