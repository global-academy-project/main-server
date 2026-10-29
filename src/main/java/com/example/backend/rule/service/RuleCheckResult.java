package com.example.backend.rule.service;

import com.example.backend.rule.entity.EvaluationResult;
import com.example.backend.rule.entity.RuleSeverity;

import java.util.List;

/**
 * 판정 응답 (check_rules 규격 5장).
 * evaluationId 는 저장했을 때만 있다 (미리 확인이면 null).
 * violations 는 어긋난 규칙만, checks 는 적용한 규칙 전부 (통과 포함). checks 는 저장하지 않는다 (같은 입력이면 같은 결과).
 */
public record RuleCheckResult(Long evaluationId, EvaluationResult result, int rulesApplied,
                              List<Violation> violations, List<String> unusedFields, List<Check> checks) {

    public record Violation(String ruleCode, String target, String actual, String expected, RuleSeverity severity,
                            String message, String action, String legalBasis, String sourceUrl) {

        static Violation from(Finding f) {
            return new Violation(f.rule().getRuleCode(), f.rule().getTarget(), f.actual(), f.expected(),
                    f.severity(), f.message(), f.action(), f.rule().getLegalBasis(), f.rule().getSourceUrl());
        }
    }

    /** 적용한 규칙 하나. status: PASS / FAIL / MISSING. message·action 은 PASS 면 null */
    public record Check(String ruleCode, String category, String target, CheckStatus status, String actual,
                        String expected, RuleSeverity severity, String message, String action, String legalBasis,
                        String sourceUrl) {

        static Check from(RuleCheck c) {
            return new Check(c.rule().getRuleCode(), c.rule().getCategory(), c.rule().getTarget(), c.status(),
                    c.actual(), c.expected(), c.rule().getSeverity(), c.message(), c.action(),
                    c.rule().getLegalBasis(), c.rule().getSourceUrl());
        }
    }

    static RuleCheckResult of(Long evaluationId, Judgement j) {
        return new RuleCheckResult(evaluationId, j.result(), j.rulesApplied(),
                j.findings().stream().map(Violation::from).toList(), j.unusedFields(),
                j.checks().stream().map(Check::from).toList());
    }
}
