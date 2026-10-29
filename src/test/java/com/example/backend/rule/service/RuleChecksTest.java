package com.example.backend.rule.service;

import com.example.backend.rule.entity.EvaluationResult;
import com.example.backend.rule.entity.Rule;
import com.example.backend.rule.entity.RuleOperator;
import com.example.backend.rule.entity.RuleSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static com.example.backend.rule.service.RuleFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** checks[]: 적용한 규칙 전부의 PASS / FAIL / MISSING (허가서 측정값 칸을 통과 항목까지 채우기 위함) */
@DisplayName("RuleEvaluator: checks (통과 포함 전체 결과)")
class RuleChecksTest {

    private final RuleEvaluator evaluator = new RuleEvaluator(JsonMapper.builder().build());

    private final List<Rule> confined = List.of(
            o2Range(),
            lt("CONFINED_CO2_MAX", "measurement.CO2", "1.500", "PCT"),
            lt("CONFINED_CO_MAX", "measurement.CO", "30.000", "PPM"),
            lt("CONFINED_H2S_MAX", "measurement.H2S", "10.000", "PPM"));

    @Test
    @DisplayName("규칙마다 하나씩, 규칙 코드 순. 통과는 PASS, 벗어나면 FAIL, 값이 없으면 MISSING")
    void statuses() {
        Judgement j = evaluator.evaluate(confined, Map.of("measurement", Map.of("O2", 17.5, "CO2", 0.8, "CO", 5)));

        assertThat(j.result()).isEqualTo(EvaluationResult.BLOCK);
        assertThat(j.checks())
                .extracting(c -> c.rule().getRuleCode(), RuleCheck::status, RuleCheck::actual)
                .containsExactly(
                        tuple("CONFINED_CO2_MAX", CheckStatus.PASS, "0.8"),
                        tuple("CONFINED_CO_MAX", CheckStatus.PASS, "5"),
                        tuple("CONFINED_H2S_MAX", CheckStatus.MISSING, null),
                        tuple("CONFINED_O2_RANGE", CheckStatus.FAIL, "17.5"));
        // FAIL·MISSING 은 findings 와 같은 규칙
        assertThat(j.findings()).extracting(f -> f.rule().getRuleCode())
                .containsExactlyInAnyOrder("CONFINED_O2_RANGE", "CONFINED_H2S_MAX");
    }

    @Test
    @DisplayName("PASS 는 기준만 있고 문구는 없다. FAIL·MISSING 은 violations 와 같은 문구")
    void texts() {
        Judgement j = evaluator.evaluate(confined, Map.of("measurement", Map.of("O2", 20.9, "CO2", 0.8, "CO", 5)));

        RuleCheck o2 = j.checks().stream().filter(c -> c.status() == CheckStatus.PASS
                && c.rule().getRuleCode().equals("CONFINED_O2_RANGE")).findFirst().orElseThrow();
        assertThat(o2.actual()).isEqualTo("20.9");
        assertThat(o2.expected()).isEqualTo("18 이상 23.5 미만 (%)");
        assertThat(o2.message()).isNull();
        assertThat(o2.action()).isNull();

        RuleCheck h2s = j.checks().stream().filter(c -> c.status() == CheckStatus.MISSING).findFirst().orElseThrow();
        assertThat(h2s.message()).isEqualTo("측정값이 없습니다.");
        assertThat(h2s.action()).isEqualTo("측정 후 입력");
    }

    @Test
    @DisplayName("확인 항목(IN [true])·필수 입력: 비면 MISSING, 다른 값이면 FAIL, 맞으면 PASS")
    void checkItems() {
        Rule extinguisher = rule("HOT_EXTINGUISHER", "HOT", "CHECK", "form.extinguisher", RuleOperator.IN,
                null, true, null, false, null, RuleSeverity.BLOCK, "[true]");
        Rule spark = rule("HOT_SPARK_PREVENTION", "HOT", "CHECK", "form.spark_prevention", RuleOperator.IN,
                null, true, null, false, null, RuleSeverity.BLOCK, "[true]");
        Rule watcher = required("HOT_FIRE_WATCHER", "form.watcher_name", RuleSeverity.BLOCK);
        Rule vapor = rule("HOT_VAPOR_REMOVED", "HOT", "CHECK", "form.flammable_vapor_removed", RuleOperator.IN,
                null, true, null, false, null, RuleSeverity.BLOCK, "[true]");

        Judgement j = evaluator.evaluate(List.of(extinguisher, spark, watcher, vapor),
                Map.of("form", Map.of("extinguisher", true, "spark_prevention", false, "watcher_name", "김감시")));

        assertThat(j.checks())
                .extracting(c -> c.rule().getRuleCode(), RuleCheck::status, RuleCheck::expected)
                .containsExactly(
                        tuple("HOT_EXTINGUISHER", CheckStatus.PASS, "확인 필요"),
                        tuple("HOT_FIRE_WATCHER", CheckStatus.PASS, "입력 필요"),
                        tuple("HOT_SPARK_PREVENTION", CheckStatus.FAIL, "확인 필요"),
                        tuple("HOT_VAPOR_REMOVED", CheckStatus.MISSING, "확인 필요"));
    }

    @Test
    @DisplayName("응답(RuleCheckResult)에 checks 가 규칙 정보와 함께 실린다")
    void response() {
        Judgement j = evaluator.evaluate(confined, Map.of("measurement", Map.of("O2", 20.9, "CO2", 0.8, "CO", 5, "H2S", 0)));
        RuleCheckResult r = RuleCheckResult.of(null, j);

        assertThat(r.result()).isEqualTo(EvaluationResult.PASS);
        assertThat(r.violations()).isEmpty();
        assertThat(r.checks()).hasSize(4).allSatisfy(c -> assertThat(c.status()).isEqualTo(CheckStatus.PASS));
        RuleCheckResult.Check o2 = r.checks().stream().filter(c -> c.ruleCode().equals("CONFINED_O2_RANGE"))
                .findFirst().orElseThrow();
        assertThat(o2.category()).isEqualTo("MEASUREMENT");
        assertThat(o2.target()).isEqualTo("measurement.O2");
        assertThat(o2.severity()).isEqualTo(RuleSeverity.BLOCK);
        assertThat(o2.legalBasis()).isEqualTo("산업안전보건기준에 관한 규칙 제618조");
    }

    @Test
    @DisplayName("적용할 규칙이 없으면 checks 도 비어 있다")
    void noRules() {
        Judgement j = evaluator.evaluate(List.of(), Map.of("measurement", Map.of("O2", 20.9)));
        assertThat(j.checks()).isEmpty();
        assertThat(j.result()).isEqualTo(EvaluationResult.PASS);
    }
}
