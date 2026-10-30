package com.example.backend.agent.client;

import tools.jackson.databind.JsonNode;

/**
 * Spring → AI 서버 기록 질문 요청 (D 확정 문서 5-1-1).
 * context 는 같은 대화의 직전 응답 context 를 그대로 (첫 질문은 null). Spring 은 안을 해석하지 않는다 (작업 등록 slots 와 같은 방식).
 * runId·siteId 는 AI 서버가 search_records 를 부를 때 그대로 채운다 (LLM 이 정하지 않음).
 */
public record RecordQueryCall(Long runId, Long siteId, String message, JsonNode context, String mode) {
}
