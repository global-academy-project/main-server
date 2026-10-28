package com.example.backend.rule.service;

import com.example.backend.rule.entity.EvaluationResult;
import com.example.backend.rule.entity.Rule;
import com.example.backend.rule.entity.RuleOperator;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.rule.repository.RuleRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 화기 규칙 시드(R__rules_seed.sql)를 실제 로컬 DB로 확인. 끝나면 롤백.
 * 시나리오 A 의 "가연성 가스 측정값 미입력 → 승인 요청 차단" 이 이 규칙으로 동작한다.
 */
@SpringBootTest
@Transactional
@DisplayName("화기 규칙 시드: 실제 DB 규칙으로 판정")
class HotRulesSeedTest {

    private static final List<String> HOT_CODES = List.of(
            "HOT_EXTINGUISHER", "HOT_FIRE_WATCHER", "HOT_FLAMMABLE_VAPOR_REMOVED", "HOT_LEL_MAX", "HOT_SPARK_PREVENTION");

    @Autowired RuleRepository ruleRepository;
    @Autowired RuleCheckService ruleCheckService;
    @Autowired EntityManager em;

    private Long newSite() {
        em.createNativeQuery("INSERT INTO sites (name) VALUES (?1)")
                .setParameter(1, "테스트현장-" + UUID.randomUUID())
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    /** 모든 화기 조치를 마친 입력 */
    private static Map<String, Object> allReady() {
        Map<String, Object> form = new HashMap<>();
        form.put("watcher_name", "김감시");
        form.put("extinguisher_ready", true);
        form.put("spark_prevention", true);
        form.put("flammable_vapor_removed", true);
        Map<String, Object> input = new HashMap<>();
        input.put("measurement", Map.of("LEL", 0));
        input.put("form", form);
        return input;
    }

    private RuleCheckResult check(Long siteId, Map<String, Object> input) {
        return ruleCheckService.check(new RuleCheckCommand(siteId, WorkType.HOT, null, null, null, input));
    }

    @Test
    @DisplayName("화기 규칙 5개가 근거와 함께 읽힌다 (IN 규칙의 허용값은 [true])")
    void hotRulesAreSeeded() {
        List<Rule> rules = ruleRepository.findByEnabledTrueAndWorkTypeInOrderByRuleCodeAsc(List.of("HOT"));

        assertThat(rules).extracting(Rule::getRuleCode).containsExactlyElementsOf(HOT_CODES);
        assertThat(rules).allSatisfy(r -> assertThat(r.getLegalBasis()).isNotBlank());
        assertThat(rules).filteredOn(r -> r.getOperator() == RuleOperator.IN)
                .allSatisfy(r -> assertThat(r.getAllowed().replace(" ", "")).isEqualTo("[true]"));
    }

    @Test
    @DisplayName("모든 조치를 마치고 측정값이 있으면 PASS")
    void allReady_passes() {
        RuleCheckResult r = check(newSite(), allReady());

        assertThat(r.result()).isEqualTo(EvaluationResult.PASS);
        assertThat(r.rulesApplied()).isEqualTo(5);
        assertThat(r.unusedFields()).isEmpty();
    }

    @Test
    @DisplayName("시나리오 A: 가연성 가스 측정값 없음 → BLOCK '측정값이 없습니다.'")
    void scenarioA_noLel_blocks() {
        Map<String, Object> input = allReady();
        input.put("measurement", Map.of());

        RuleCheckResult r = check(newSite(), input);

        assertThat(r.result()).isEqualTo(EvaluationResult.BLOCK);
        assertThat(r.violations()).hasSize(1);
        RuleCheckResult.Violation v = r.violations().get(0);
        assertThat(v.ruleCode()).isEqualTo("HOT_LEL_MAX");
        assertThat(v.actual()).isNull();
        assertThat(v.message()).isEqualTo("측정값이 없습니다.");
        assertThat(v.expected()).isEqualTo("10 미만 (%LEL)");
    }

    @Test
    @DisplayName("가연성 가스 경계: 9.9 %LEL 통과, 10 %LEL 이면 BLOCK (폭발하한의 10% 도달 시 중단)")
    void lelBoundary() {
        Long siteId = newSite();
        Map<String, Object> ok = allReady();
        ok.put("measurement", Map.of("LEL", 9.9));
        Map<String, Object> stop = allReady();
        stop.put("measurement", Map.of("LEL", 10));

        assertThat(check(siteId, ok).result()).isEqualTo(EvaluationResult.PASS);
        RuleCheckResult r = check(siteId, stop);
        assertThat(r.result()).isEqualTo(EvaluationResult.BLOCK);
        assertThat(r.violations().get(0).ruleCode()).isEqualTo("HOT_LEL_MAX");
        assertThat(r.violations().get(0).action()).isEqualTo("화기작업 중단, 환기 후 재측정");
    }

    @Test
    @DisplayName("화재감시자 없음, 소화기 false, 입력 없음 → 각각 위반")
    void missingMeasures_block() {
        Map<String, Object> input = allReady();
        @SuppressWarnings("unchecked")
        Map<String, Object> form = (Map<String, Object>) input.get("form");
        form.put("watcher_name", "");
        form.put("extinguisher_ready", false);
        form.remove("spark_prevention");

        RuleCheckResult r = check(newSite(), input);

        assertThat(r.result()).isEqualTo(EvaluationResult.BLOCK);
        assertThat(r.violations()).extracting(RuleCheckResult.Violation::ruleCode)
                .containsExactly("HOT_EXTINGUISHER", "HOT_FIRE_WATCHER", "HOT_SPARK_PREVENTION");
        assertThat(r.violations()).filteredOn(v -> v.ruleCode().equals("HOT_EXTINGUISHER"))
                .singleElement().satisfies(v -> {
                    assertThat(v.actual()).isEqualTo("false");
                    assertThat(v.expected()).isEqualTo("확인 필요");
                    assertThat(v.legalBasis()).startsWith("산업안전보건기준에 관한 규칙 제241조제2항제3호");
                });
    }
}
