package com.example.backend.agent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

/**
 * agent_runs.input_json (작업 등록). 실행을 만들 때 저장하고, AI 서버를 부를 때 다시 읽는다.
 * slots 는 직전 실행 응답의 slots 그대로 (첫 요청은 null). JSON null 도 Java null 로 맞춘다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunInput(String message, JsonNode slots, Long permitId) {

    public RunInput {
        if (slots != null && slots.isNull()) {
            slots = null;
        }
    }
}