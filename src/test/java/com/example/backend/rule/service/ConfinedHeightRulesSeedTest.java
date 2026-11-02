package com.example.backend.rule.service;

import com.example.backend.rule.entity.EvaluationResult;
import com.example.backend.rule.entity.Rule;
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

/** 밀폐 작업 조치·고소 규칙 시드(R__rules_seed.sql, 11/02 추가)를 실제 로컬 DB로 확인. 끝나면 롤백 */
@SpringBootTest
@Transactional
@DisplayName("밀폐·고소 규칙 시드: 실제 DB 규칙으로 판정")
class ConfinedHeightRulesSeedTest {

    @Autowired RuleRepository ruleRepository;
    @Autowired RuleCheckService ruleCheckService;
    @Autowired EntityManager em;

    private Long newSite() {
        em.createNativeQuery("INSERT INTO sites (name) VALUES (?1)")
                .setParameter(1, "테스트현장-" + UUID.randomUUID())
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    private RuleCheckResult check(WorkType type, Map<String, Object> input) {
        return ruleCheckService.check(new RuleCheckCommand(newSite(), type, null, null, null, input));
    }

    private static Map<String, Object> confinedReady() {
        Map<String, Object> form = new HashMap<>();
        form.put("watcher_name", "김감시");
        form.put("ventilation_done", true);
        form.put("rescue_equipment_ready", true);
        Map<String, Object> input = new HashMap<>();
        input.put("measurement", Map.of("O2", 20.9, "CO2", 0.1, "CO", 0, "H2S", 0, "LEL", 0));
        input.put("form", form);
        return input;
    }

    private static Map<String, Object> heightReady() {
        Map<String, Object> form = new HashMap<>();
        form.put("fall_protection", "WORK_PLATFORM");
        form.put("helmet_worn", true);
        form.put("harness_worn", true);
        return new HashMap<>(Map.of("form", form));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> form(Map<String, Object> input) {
        return (Map<String, Object>) input.get("form");
    }

    @Test
    @DisplayName("밀폐 8개(측정 5 + 감시인·환기·대피 기구), 고소 3개가 근거와 함께 읽힌다")
    void seeded() {
        assertThat(ruleRepository.findByEnabledTrueAndWorkTypeInOrderByRuleCodeAsc(List.of("CONFINED")))
                .extracting(Rule::getRuleCode)
                .containsExactlyInAnyOrder("CONFINED_CO2_MAX", "CONFINED_CO_MAX", "CONFINED_H2S_MAX", "CONFINED_LEL_MAX",
                        "CONFINED_O2_RANGE", "CONFINED_RESCUE_EQUIPMENT", "CONFINED_VENTILATION", "CONFINED_WATCHER");
        List<Rule> height = ruleRepository.findByEnabledTrueAndWorkTypeInOrderByRuleCodeAsc(List.of("HEIGHT"));
        assertThat(height).extracting(Rule::getRuleCode)
                .containsExactlyInAnyOrder("HEIGHT_FALL_PROTECTION", "HEIGHT_HARNESS", "HEIGHT_HELMET");
        assertThat(height).allSatisfy(r -> assertThat(r.getLegalBasis()).startsWith("산업안전보건기준에 관한 규칙 제"));
    }

    @Test
    @DisplayName("밀폐: 측정값 정상 + 감시인·환기·대피 기구 확인이면 PASS, 감시인 없고 대피 기구 false 면 각각 BLOCK")
    void confined() {
        RuleCheckResult ok = check(WorkType.CONFINED, confinedReady());
        assertThat(ok.result()).isEqualTo(EvaluationResult.PASS);
        assertThat(ok.rulesApplied()).isEqualTo(8);

        Map<String, Object> input = confinedReady();
        form(input).put("watcher_name", " ");
        form(input).put("rescue_equipment_ready", false);
        RuleCheckResult r = check(WorkType.CONFINED, input);

        assertThat(r.result()).isEqualTo(EvaluationResult.BLOCK);
        assertThat(r.violations()).extracting(RuleCheckResult.Violation::ruleCode)
                .containsExactly("CONFINED_RESCUE_EQUIPMENT", "CONFINED_WATCHER");
        assertThat(r.violations()).filteredOn(v -> v.ruleCode().equals("CONFINED_WATCHER")).singleElement()
                .satisfies(v -> assertThat(v.legalBasis()).isEqualTo("산업안전보건기준에 관한 규칙 제623조"));
    }

    @Test
    @DisplayName("고소: 작업발판·추락방호망·안전대 중 하나 + 안전모·안전대 확인이면 PASS. 사다리·빈 값은 BLOCK")
    void height() {
        for (String method : List.of("WORK_PLATFORM", "SAFETY_NET", "SAFETY_HARNESS")) {
            Map<String, Object> input = heightReady();
            form(input).put("fall_protection", method);
            assertThat(check(WorkType.HEIGHT, input).result()).as(method).isEqualTo(EvaluationResult.PASS);
        }

        Map<String, Object> ladder = heightReady();
        form(ladder).put("fall_protection", "LADDER");
        RuleCheckResult r = check(WorkType.HEIGHT, ladder);
        assertThat(r.result()).isEqualTo(EvaluationResult.BLOCK);
        assertThat(r.violations()).singleElement().satisfies(v -> {
            assertThat(v.ruleCode()).isEqualTo("HEIGHT_FALL_PROTECTION");
            assertThat(v.actual()).isEqualTo("LADDER");
            assertThat(v.expected()).isEqualTo("WORK_PLATFORM, SAFETY_NET, SAFETY_HARNESS 중 하나");
        });

        RuleCheckResult empty = check(WorkType.HEIGHT, Map.of());
        assertThat(empty.violations()).extracting(RuleCheckResult.Violation::ruleCode)
                .containsExactly("HEIGHT_FALL_PROTECTION", "HEIGHT_HARNESS", "HEIGHT_HELMET");
    }
}
