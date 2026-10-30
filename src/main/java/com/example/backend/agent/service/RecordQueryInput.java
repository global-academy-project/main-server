package com.example.backend.agent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

/** agent_runs.input_json (기록 질문). context 는 직전 응답의 context 그대로 (첫 질문은 null) */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecordQueryInput(String message, JsonNode context) {

    public RecordQueryInput {
        if (context != null && context.isNull()) {
            context = null;
        }
    }
}
