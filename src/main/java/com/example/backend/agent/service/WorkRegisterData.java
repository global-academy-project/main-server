package com.example.backend.agent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * AI 서버 작업 등록 응답(WorkRegisterResult) 중 Spring 이 읽는 부분만.
 * 나머지(drafts, classification 등)는 agent_runs.output_json 에 통째로 남기고 화면이 그대로 읽는다.
 * slots 는 해석하지 않고 JSON 그대로 다음 요청에 보낸다 (B 답신 2장).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkRegisterData(String runStatus, JsonNode slots, Ask ask, ApprovalGate approvalGate,
                        List<CitationItem> citations, List<StepItem> steps) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ask(String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApprovalGate(Boolean canRequest, List<String> reasons) {
    }

    /** kind: KB(청크 UUID) / RULE(규칙 코드) / MSDS(CAS 또는 물질 이름). sourceType·kbCollection 은 KB 저장에 필요 (아직 AI 서버가 안 줌) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CitationItem(String kind, String refId, String source, String snippet, String sourceType, String kbCollection) {
    }

    /** kind: TOOL / PHASE, status: OK / ERROR */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepItem(Integer seq, String kind, String name, String toolName, String status, JsonNode input,
                    JsonNode output, Integer elapsedMs, String errorCode) {
    }
}
