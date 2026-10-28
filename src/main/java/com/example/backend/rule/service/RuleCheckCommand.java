package com.example.backend.rule.service;

import com.example.backend.rule.entity.EvaluationTarget;
import com.example.backend.rule.entity.WorkType;

import java.util.Map;

/**
 * 판정 요청. 컨트롤러(웹·내부 API)가 만들어 넘긴다.
 *  - targetType·targetId: 둘 다 있으면 판정을 저장, 둘 다 없으면 판정만 (미리 확인)
 *  - agentRunId: 에이전트 실행에서 부른 판정이면 그 실행 ID
 *  - input: { "measurement": {...}, "form": {...} }
 */
public record RuleCheckCommand(Long siteId, WorkType workType, EvaluationTarget targetType, Long targetId,
                               Long agentRunId, Map<String, Object> input) {
}
