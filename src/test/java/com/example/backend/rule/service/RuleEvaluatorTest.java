package com.example.backend.rule.service;

import com.example.backend.common.exception.ErrorCode;
import com.example.backend.rule.entity.EvaluationResult;
import com.example.backend.rule.entity.Rule;
import com.example.backend.rule.entity.RuleOperator;
import com.example.backend.rule.entity.RuleSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.example.backend.rule.service.RuleFixtures.*;
import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RuleEvaluator: 규칙 판정")
class RuleEvaluatorTest {

    private final RuleEvaluator evaluator = new RuleEvaluator(JsonMapper.builder().build());

    /** 시드와 같은 밀폐공간 측정 규칙 4개 */
    private final List<Rule> confined = List.of(
            o2Range(),
            lt("CONFINED_CO2_MAX", "measurement.CO2", "1.500", "PCT"),
            lt("CONFINED_CO_MAX", "measurement.CO", "30.000", "PPM"),
            lt("CONFINED_H2S_MAX", "measurement.H2S", "10.000", "PPM"));

    private static Map<String, Object> measurement(Object o2, Object co2, Object co, Object h2s) {
        Map<String, Object> m = new HashMap<>();
        if (o2 != null) m.put("O2", o2);
        if (co2 != null) m.put("CO2", co2);
        if (co != null) m.put("CO", co);
        if (h2s != null) m.put("H2S", h2s);
        return Map.of("measurement", m);
    }

    @Test
    @DisplayName("시나리오 B: 산소 17.5% → BLOCK, 기준·조치·근거 조항을 함께 준다")
    void scenarioB_lowOxygen_blocks() {
        Judgement j = evaluator.evaluate(confined, measurement(17.5, 0.8, 5, 0));

        assertThat(j.result()).isEqualTo(EvaluationResult.BLOCK);
        assertThat(j.rulesApplied()).isEqualTo(4);
        assertThat(j.findings()).hasSize(1);
        Finding f = j.findings().get(0);
        assertThat(f.rule().getRuleCode()).isEqualTo("CONFINED_O2_RANGE");
        assertThat(f.actual()).isEqualTo("17.5");
        assertThat(f.expected()).isEqualTo("18 이상 23.5 미만 (%)");
        assertThat(f.message()).isEqualTo("산소농도가 적정공기 기준(18% 이상 23.5% 미만)을 벗어났습니다.");
        assertThat(f.action()).isEqualTo("환기 후 재측정");
        assertThat(f.rule().getLegalBasis()).isEqualTo("산업안전보건기준에 관한 규칙 제618조");
    }

    @Test
    @DisplayName("모든 값이 기준 안이면 PASS, 위반 없음")
    void allNormal_passes() {
        Judgement j = evaluator.evaluate(confined, measurement(20.9, 0.04, 0, 0));

        assertThat(j.result()).isEqualTo(EvaluationResult.PASS);
        assertThat(j.findings()).isEmpty();
        assertThat(j.unusedFields()).isEmpty();
    }

    @ParameterizedTest(name = "산소 {0}% → {1}")
    @CsvSource({"17.999, BLOCK", "18, PASS", "18.0, PASS", "23.499, PASS", "23.5, BLOCK", "25, BLOCK"})
    @DisplayName("RANGE 경계: 18 이상은 통과, 23.5 는 미만 기준이라 위반")
    void o2Boundaries(String o2, EvaluationResult expected) {
        Judgement j = evaluator.evaluate(List.of(o2Range()), measurement(new BigDecimal(o2), null, null, null));

        assertThat(j.result()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "CO {0}ppm → {1}")
    @CsvSource({"29.999, PASS", "30, BLOCK"})
    @DisplayName("LT 경계: 기준값과 같으면 위반 (미만이어야 함)")
    void ltBoundary(String co, EvaluationResult expected) {
        Judgement j = evaluator.evaluate(List.of(lt("CONFINED_CO_MAX", "measurement.CO", "30.000", "PPM")),
                measurement(null, null, new BigDecimal(co), null));

        assertThat(j.result()).isEqualTo(expected);
        if (expected == EvaluationResult.BLOCK) {
            assertThat(j.findings().get(0).expected()).isEqualTo("30 미만 (ppm)");
        }
    }

    @ParameterizedTest(name = "{0} 기준 {1}, 값 {2} → 위반 {3}")
    @CsvSource({
            "GTE, 2, 2, false", "GTE, 2, 1.9, true",
            "GT, 2, 2, true", "GT, 2, 2.1, false",
            "LTE, 5, 5, false", "LTE, 5, 5.1, true",
            "EQ, 1, 1.0, false", "EQ, 1, 2, true"})
    @DisplayName("GTE·GT·LTE·EQ 연산자")
    void otherOperators(RuleOperator op, String base, String value, boolean violated) {
        boolean usesMin = op == RuleOperator.GTE || op == RuleOperator.GT || op == RuleOperator.EQ;
        Rule rule = rule("STAFF_" + op, "HOT", "STAFFING", "form.count", op,
                usesMin ? base : null, true, usesMin ? null : base, false, "PERSON", RuleSeverity.BLOCK, null);

        Judgement j = evaluator.evaluate(List.of(rule), Map.of("form", Map.of("count", new BigDecimal(value))));

        assertThat(j.findings()).hasSize(violated ? 1 : 0);
    }

    @Test
    @DisplayName("측정값이 빠지면 위반 (값을 빼먹어서 통과되지 않게)")
    void missingMeasurement_isViolation() {
        Judgement j = evaluator.evaluate(confined, measurement(20.9, 0.04, null, 0));

        assertThat(j.result()).isEqualTo(EvaluationResult.BLOCK);
        Finding f = j.findings().get(0);
        assertThat(f.rule().getRuleCode()).isEqualTo("CONFINED_CO_MAX");
        assertThat(f.actual()).isNull();
        assertThat(f.message()).isEqualTo("측정값이 없습니다.");
        assertThat(f.action()).isEqualTo("측정 후 입력");
        assertThat(f.expected()).isEqualTo("30 미만 (ppm)");
    }

    @Test
    @DisplayName("입력이 아예 없으면 측정 규칙 4개 모두 위반")
    void emptyInput_allMissing() {
        Judgement j = evaluator.evaluate(confined, null);

        assertThat(j.result()).isEqualTo(EvaluationResult.BLOCK);
        assertThat(j.findings()).hasSize(4);
    }

    @Test
    @DisplayName("측정값이 숫자가 아니면(문자열 \"17.5\") INVALID_REQUEST")
    void nonNumeric_invalidRequest() {
        expectCode(() -> evaluator.evaluate(confined, measurement("17.5", 0.04, 0, 0)), ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("REQUIRED: 빈 문자열이면 위반, 값이 있으면 통과")
    void requiredField() {
        Rule watcher = required("CONFINED_WATCHER_REQUIRED", "form.watcher_name", RuleSeverity.BLOCK);

        Judgement blank = evaluator.evaluate(List.of(watcher), Map.of("form", Map.of("watcher_name", " ")));
        Judgement filled = evaluator.evaluate(List.of(watcher), Map.of("form", Map.of("watcher_name", "김감시")));

        assertThat(blank.result()).isEqualTo(EvaluationResult.BLOCK);
        assertThat(blank.findings().get(0).expected()).isEqualTo("입력 필요");
        assertThat(blank.findings().get(0).actual()).isNull();
        assertThat(filled.result()).isEqualTo(EvaluationResult.PASS);
    }

    @Test
    @DisplayName("IN: 허용 목록 밖이면 위반, 숫자는 5 와 5.0 을 같게 본다")
    void in() {
        Rule rule = rule("HOT_EXT_TYPE", "HOT", "REQUIRED_FIELD", "form.extinguisher", RuleOperator.IN,
                null, true, null, false, null, RuleSeverity.WARN, "[\"ABC\", \"CO2\", 5]");

        Judgement bad = evaluator.evaluate(List.of(rule), Map.of("form", Map.of("extinguisher", "WATER")));
        Judgement ok = evaluator.evaluate(List.of(rule), Map.of("form", Map.of("extinguisher", "CO2")));
        Judgement num = evaluator.evaluate(List.of(rule), Map.of("form", Map.of("extinguisher", 5.0)));

        assertThat(bad.result()).isEqualTo(EvaluationResult.WARN);
        assertThat(bad.findings().get(0).expected()).isEqualTo("ABC, CO2, 5 중 하나");
        assertThat(ok.result()).isEqualTo(EvaluationResult.PASS);
        assertThat(num.result()).isEqualTo(EvaluationResult.PASS);
    }

    @Test
    @DisplayName("WARN 만 있으면 WARN, BLOCK 이 섞이면 BLOCK 이고 BLOCK 이 먼저 온다")
    void severityOrder() {
        Rule warn = required("A_WARN", "form.memo", RuleSeverity.WARN);
        Rule block = required("Z_BLOCK", "form.watcher_name", RuleSeverity.BLOCK);

        Judgement onlyWarn = evaluator.evaluate(List.of(warn), Map.of());
        Judgement both = evaluator.evaluate(List.of(warn, block), Map.of());

        assertThat(onlyWarn.result()).isEqualTo(EvaluationResult.WARN);
        assertThat(both.result()).isEqualTo(EvaluationResult.BLOCK);
        assertThat(both.findings()).extracting(f -> f.rule().getRuleCode()).containsExactly("Z_BLOCK", "A_WARN");
    }

    @Test
    @DisplayName("규칙이 보지 않은 입력은 unusedFields 로 알려 준다 (이름 오타 찾기)")
    void unusedFields() {
        Map<String, Object> input = Map.of(
                "measurement", Map.of("O2", 20.9, "O3", 1),
                "form", Map.of("watcher", "김감시"));

        Judgement j = evaluator.evaluate(List.of(o2Range()), input);

        assertThat(j.unusedFields()).containsExactly("form.watcher", "measurement.O3");
    }
}
