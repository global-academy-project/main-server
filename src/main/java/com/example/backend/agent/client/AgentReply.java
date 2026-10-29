package com.example.backend.agent.client;

import tools.jackson.databind.JsonNode;

/** AI 서버 성공 응답의 data (WorkRegisterResult 그대로) */
public record AgentReply(JsonNode data) {
}
