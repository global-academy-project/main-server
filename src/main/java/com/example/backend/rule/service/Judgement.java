package com.example.backend.rule.service;

import com.example.backend.rule.entity.EvaluationResult;

import java.util.List;

/**
 * 판정 결과 (저장 전).
 *  - findings: BLOCK 먼저, 그다음 규칙 코드 순
 *  - unusedFields: 입력에 있었지만 어떤 규칙도 보지 않은 값의 경로 (이름 오타 찾기용)
 *  - checks: 적용한 규칙 전부의 결과 (통과 포함, 규칙 코드 순)
 */
public record Judgement(EvaluationResult result, int rulesApplied, List<Finding> findings, List<String> unusedFields,
                        List<RuleCheck> checks) {
}
