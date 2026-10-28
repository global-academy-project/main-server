package com.example.backend.rule.service;

import com.example.backend.rule.entity.Rule;
import com.example.backend.rule.entity.RuleOperator;
import com.example.backend.rule.entity.RuleSeverity;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

/** 테스트용 규칙. Rule 은 읽기 전용 엔티티라 리플렉션으로 채운다 */
final class RuleFixtures {

    private static long seq = 100;

    private RuleFixtures() {
    }

    static Rule rule(String code, String workType, String category, String target, RuleOperator op,
                     String min, boolean minInclusive, String max, boolean maxInclusive, String unit,
                     RuleSeverity severity, String allowed) {
        Rule r = BeanUtils.instantiateClass(Rule.class);
        ReflectionTestUtils.setField(r, "id", ++seq);
        ReflectionTestUtils.setField(r, "ruleCode", code);
        ReflectionTestUtils.setField(r, "version", 1);
        ReflectionTestUtils.setField(r, "workType", workType);
        ReflectionTestUtils.setField(r, "category", category);
        ReflectionTestUtils.setField(r, "target", target);
        ReflectionTestUtils.setField(r, "operator", op);
        ReflectionTestUtils.setField(r, "minValue", min == null ? null : new BigDecimal(min));
        ReflectionTestUtils.setField(r, "minInclusive", minInclusive);
        ReflectionTestUtils.setField(r, "maxValue", max == null ? null : new BigDecimal(max));
        ReflectionTestUtils.setField(r, "maxInclusive", maxInclusive);
        ReflectionTestUtils.setField(r, "unit", unit);
        ReflectionTestUtils.setField(r, "severity", severity);
        ReflectionTestUtils.setField(r, "message", code + " 위반");
        ReflectionTestUtils.setField(r, "action", code + " 조치");
        ReflectionTestUtils.setField(r, "legalBasis", "근거 " + code);
        ReflectionTestUtils.setField(r, "allowed", allowed);
        ReflectionTestUtils.setField(r, "enabled", true);
        return r;
    }

    /** R__rules_seed.sql 의 밀폐공간 산소 규칙과 같은 값 (18.000 이상 23.500 미만 %) */
    static Rule o2Range() {
        Rule r = rule("CONFINED_O2_RANGE", "CONFINED", "MEASUREMENT", "measurement.O2", RuleOperator.RANGE,
                "18.000", true, "23.500", false, "PCT", RuleSeverity.BLOCK, null);
        ReflectionTestUtils.setField(r, "message", "산소농도가 적정공기 기준(18% 이상 23.5% 미만)을 벗어났습니다.");
        ReflectionTestUtils.setField(r, "action", "환기 후 재측정");
        ReflectionTestUtils.setField(r, "legalBasis", "산업안전보건기준에 관한 규칙 제618조");
        return r;
    }

    static Rule lt(String code, String target, String max, String unit) {
        return rule(code, "CONFINED", "MEASUREMENT", target, RuleOperator.LT,
                null, true, max, false, unit, RuleSeverity.BLOCK, null);
    }

    static Rule required(String code, String target, RuleSeverity severity) {
        return rule(code, "CONFINED", "REQUIRED_FIELD", target, RuleOperator.REQUIRED,
                null, true, null, false, null, severity, null);
    }
}
