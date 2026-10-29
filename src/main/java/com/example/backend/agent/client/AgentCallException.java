package com.example.backend.agent.client;

import tools.jackson.databind.JsonNode;

/**
 * AI 서버 호출 실패. code 는 AI 서버 error.code (연결 실패·시간 초과면 AI_UNAVAILABLE).
 * steps 는 실패 응답에 실려 온 그때까지의 단계 (없으면 null, B 답신 3장).
 */
public class AgentCallException extends RuntimeException {

    private final String code;
    private final transient JsonNode steps;

    public AgentCallException(String code, String message, JsonNode steps) {
        super(message);
        this.code = code;
        this.steps = steps;
    }

    public String getCode() { return code; }

    public JsonNode getSteps() { return steps; }
}
