package com.example.backend.agent.dto;

import com.example.backend.agent.entity.AgentRunStatus;

/** 메시지를 받아 실행을 시작했음 (202). 화면은 runId 로 상태를 조회(폴링)한다 */
public record AgentRunStarted(Long conversationId, Long runId, AgentRunStatus status) {
}
