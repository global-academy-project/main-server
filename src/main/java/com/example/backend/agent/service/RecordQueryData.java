package com.example.backend.agent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * AI 서버 기록 질문 응답(RecordQueryResult) 중 Spring 이 읽는 부분. 나머지는 output_json 에 통째로 남기고 화면이 읽는다.
 *  - runStatus: DONE(답변) / WAITING_INPUT(질문이 애매해 되물음)
 *  - answer: 화면에 보일 답변 문장, ask.message: 되묻기 문장
 *  - queries: 쓴 조회 (search_records 의 queryType·conditions·total·truncated) → 화면 "조회 조건"
 *  - evidence: 답변 근거 기록 {type, id, label} → 근거 링크. Spring 이 그 현장 기록인지 확인하고 저장한다
 *  - context: 다음 질문에 그대로 다시 보낼 값 (Spring 은 해석하지 않음)
 *  - steps: 작업 등록과 같은 모양 (PHASE query.plan / TOOL search_records / PHASE verification)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecordQueryData(String runStatus, String answer, WorkRegisterData.Ask ask, JsonNode queries,
                              List<EvidenceItem> evidence, JsonNode context, List<WorkRegisterData.StepItem> steps) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvidenceItem(String type, Long id, String label) {
    }
}
