package com.example.backend.rule.service;

import com.example.backend.rule.entity.EvaluationResult;
import com.example.backend.rule.entity.RuleSeverity;

import java.util.List;

/**
 * 판정 응답 (check_rules 규격 5장).
 * evaluationId 는 저장했을 때만 있다 (미리 확인이면 null).
 */
public record RuleCheckResult(Long evaluationId, EvaluationResult result, int rulesApplied,
                              List<Violation> violations, List<String> unusedFields) {

    public record Violation(String ruleCode, String target, String actual, String expected, RuleSeverity severity,
                            String message, String action, String legalBasis, String sourceUrl) {

        static Violation from(Finding f) {
            return new Violation(f.rule().getRuleCode(), f.rule().getTarget(), f.actual(), f.expected(),
                    f.severity(), f.message(), f.action(), f.rule().getLegalBasis(), f.rule().getSourceUrl());
        }
    }

    static RuleCheckResult of(Long evaluationId, Judgement j) {
        return new RuleCheckResult(evaluationId, j.result(), j.rulesApplied(),
                j.findings().stream().map(Violation::from).toList(), j.unusedFields());
    }
}
