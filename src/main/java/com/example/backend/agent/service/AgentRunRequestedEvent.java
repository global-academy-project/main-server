package com.example.backend.agent.service;

/** 실행을 저장했음. 커밋 후 AgentProcessingListener 가 AI 서버를 부른다 */
public record AgentRunRequestedEvent(Long runId) {
}
