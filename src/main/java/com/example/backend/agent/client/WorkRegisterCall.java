package com.example.backend.agent.client;

import tools.jackson.databind.JsonNode;

/**
 * Spring → AI 서버 작업 등록 요청 (B 답신 1장).
 * slots 는 직전 응답의 slots 를 그대로 (첫 요청은 null). Spring 은 slots 안을 해석하지 않는다.
 */
public record WorkRegisterCall(Long runId, Long siteId, Long permitId, String message, JsonNode slots, String mode) {
}
