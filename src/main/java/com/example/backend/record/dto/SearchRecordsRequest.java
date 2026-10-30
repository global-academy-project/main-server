package com.example.backend.record.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * search_records 요청 (AI 서버 → Spring, D 확정 문서 5-3).
 * siteId·agentRunId 는 에이전트가 정하지 않고 AI 서버 코드가 실행 요청 값으로 채운다 (다른 현장 조회 방지).
 * params: 조회 유형별 조건. from·to(YYYY-MM-DD, 양 끝 포함)는 모든 유형에 필수, 모르는 조건은 400
 */
public record SearchRecordsRequest(@NotNull Long siteId, @NotNull Long agentRunId, @NotBlank String queryType,
                                   Map<String, Object> params) {
}
