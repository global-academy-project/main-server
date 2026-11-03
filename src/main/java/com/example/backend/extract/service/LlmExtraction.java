package com.example.backend.extract.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * LLM 추출 결과 (ai-model POST /api/v1/extract/rule-input 의 data). 서버는 이 값을 그대로 믿지 않고
 * ExtractionMerger 가 원문·단위·규칙 추출과 대조한 뒤에만 쓴다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmExtraction(List<Measurement> measurements, List<WorkTypeHit> workTypes, String watcherName,
                            String watcherEvidence) {

    /** unit: PCT / PPM / PCT_LEL (규칙 단위 코드). evidence: 원문에서 그대로 옮긴 부분 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Measurement(String item, BigDecimal value, String unit, String evidence) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkTypeHit(String type, String evidence) {
    }
}
