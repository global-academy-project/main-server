package com.example.backend.agent.dto;

import com.example.backend.agent.entity.AgentRunStatus;
import com.example.backend.agent.entity.AgentTaskType;

import java.time.LocalDateTime;

/**
 * GET /api/v1/agent/conversations 의 한 줄 (내 지난 대화).
 * lastRunId·lastRunStatus 는 그 대화의 마지막 실행 — 화면이 다시 열 때 마지막 답(근거 기록)을 GET /runs/{id} 로 받는다.
 */
public record AgentConversationItem(Long conversationId, Long siteId, AgentTaskType taskType, String title,
                                    int messageCount, LocalDateTime lastMessageAt, boolean pinned,
                                    Long lastRunId, AgentRunStatus lastRunStatus) {
}
