package com.example.backend.rule.service;

import com.example.backend.rule.entity.EvaluationResult;
import com.example.backend.rule.entity.EvaluationTarget;
import com.example.backend.rule.entity.Rule;
import com.example.backend.rule.entity.RuleEvaluation;
import com.example.backend.rule.entity.RuleOperator;
import com.example.backend.rule.entity.RuleSeverity;
import com.example.backend.rule.entity.RuleViolation;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.rule.repository.RuleEvaluationRepository;
import com.example.backend.rule.repository.RuleRepository;
import com.example.backend.rule.repository.RuleViolationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 로컬 DB로 확인 (contextLoads 와 같은 DB). 테스트가 끝나면 롤백되어 데이터가 남지 않는다.
 *  - R__rules_seed 의 규칙이 엔티티로 제대로 읽히는지 (DECIMAL, BOOLEAN, enum)
 *  - 판정 기록이 JSON 컬럼·외래키·CHECK 제약을 통과해 저장되는지
 *  - 저장된 판정은 수정할 수 없는지 (V9 트리거)
 */
@SpringBootTest
@Transactional
@DisplayName("규칙 엔진 DB 통합: 시드 규칙 읽기, 판정 기록 저장")
class RuleDbIntegrationTest {

    @Autowired RuleRepository ruleRepository;
    @Autowired RuleEvaluationRepository evaluationRepository;
    @Autowired RuleViolationRepository violationRepository;
    @Autowired RuleCheckService ruleCheckService;
    @Autowired RuleEvaluator evaluator;
    @Autowired EntityManager em;

    private Long newSite() {
        em.createNativeQuery("INSERT INTO sites (name) VALUES (?1)")
                .setParameter(1, "테스트현장-" + UUID.randomUUID())
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    @Test
    @DisplayName("시드의 밀폐공간 규칙(산소·탄산가스·일산화탄소·황화수소·가연성 가스)이 값 그대로 읽힌다")
    void seedRulesAreMapped() {
        List<Rule> rules = ruleRepository.findByEnabledTrueAndWorkTypeInOrderByRuleCodeAsc(List.of("CONFINED", "ALL"));

        assertThat(rules).extracting(Rule::getRuleCode)
                .contains("CONFINED_CO2_MAX", "CONFINED_CO_MAX", "CONFINED_H2S_MAX", "CONFINED_LEL_MAX", "CONFINED_O2_RANGE");
        Rule o2 = rules.stream().filter(r -> r.getRuleCode().equals("CONFINED_O2_RANGE")).findFirst().orElseThrow();
        assertThat(o2.getOperator()).isEqualTo(RuleOperator.RANGE);
        assertThat(o2.getMinValue()).isEqualByComparingTo(new BigDecimal("18"));
        assertThat(o2.getMaxValue()).isEqualByComparingTo(new BigDecimal("23.5"));
        assertThat(o2.isMinInclusive()).isTrue();
        assertThat(o2.isMaxInclusive()).isFalse();
        assertThat(o2.getUnit()).isEqualTo("PCT");
        assertThat(o2.getSeverity()).isEqualTo(RuleSeverity.BLOCK);
        assertThat(o2.getLegalBasis()).isEqualTo("산업안전보건기준에 관한 규칙 제618조");
    }

    @Test
    @DisplayName("시나리오 B를 실제 시드 규칙으로: 산소 17.5% → BLOCK (미리 확인, 저장 안 함)")
    void scenarioB_withSeedRules() {
        Long siteId = newSite();
        long before = evaluationRepository.count();

        RuleCheckResult r = ruleCheckService.check(new RuleCheckCommand(siteId, WorkType.CONFINED, null, null, null,
                Map.of("measurement", Map.of("O2", 17.5, "CO2", 0.8, "CO", 5, "H2S", 0, "LEL", 0))));

        assertThat(r.result()).isEqualTo(EvaluationResult.BLOCK);
        assertThat(r.evaluationId()).isNull();
        assertThat(r.violations()).hasSize(1);
        assertThat(r.violations().get(0).ruleCode()).isEqualTo("CONFINED_O2_RANGE");
        assertThat(r.violations().get(0).expected()).isEqualTo("18 이상 23.5 미만 (%)");
        assertThat(r.violations().get(0).action()).isEqualTo("환기 후 재측정");
        assertThat(evaluationRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("판정·위반이 JSON 스냅샷과 함께 저장되고, 저장된 판정은 수정할 수 없다 (V9)")
    void evaluationIsStoredAndImmutable() {
        Long siteId = newSite();
        Rule o2 = ruleRepository.findByEnabledTrueAndWorkTypeInOrderByRuleCodeAsc(List.of("CONFINED")).stream()
                .filter(r -> r.getRuleCode().equals("CONFINED_O2_RANGE")).findFirst().orElseThrow();
        Map<String, Object> input = Map.of("measurement", Map.of("O2", 17.5));
        Judgement j = evaluator.evaluate(List.of(o2), input);

        // target_id 는 외래키가 없어 문서 없이도 저장 가능 (문서 존재 확인은 서비스가 한다)
        RuleEvaluation saved = evaluationRepository.saveAndFlush(new RuleEvaluation(siteId, EvaluationTarget.PERMIT, 999_999L,
                j.result(), "{\"measurement\":{\"O2\":17.5}}", j.rulesApplied(), null, LocalDateTime.now()));
        Finding f = j.findings().get(0);
        violationRepository.saveAllAndFlush(List.of(new RuleViolation(saved.getId(), o2.getId(), f.actual(),
                f.expected(), f.severity(), f.message(), o2.getLegalBasis())));
        em.clear();

        Object o2InDb = em.createNativeQuery(
                        "SELECT CAST(JSON_EXTRACT(input_snapshot, '$.measurement.O2') AS CHAR) FROM rule_evaluations WHERE id = ?1")
                .setParameter(1, saved.getId()).getSingleResult();
        assertThat(new BigDecimal(String.valueOf(o2InDb))).isEqualByComparingTo("17.5");
        assertThat(evaluationRepository.findById(saved.getId()).orElseThrow().getResult()).isEqualTo(EvaluationResult.BLOCK);
        List<RuleViolation> viols = violationRepository.findByEvaluationIdOrderByIdAsc(saved.getId());
        assertThat(viols).hasSize(1);
        assertThat(viols.get(0).getExpected()).isEqualTo("18 이상 23.5 미만 (%)");
        assertThat(viols.get(0).getActualValue()).isEqualTo("17.5");

        // 마지막에 둔다: 트리거 오류 뒤에는 이 트랜잭션에서 더 쓰지 않음 (테스트 끝에 롤백)
        assertThatThrownBy(() -> em.createNativeQuery("UPDATE rule_evaluations SET result = 'PASS' WHERE id = ?1")
                .setParameter(1, saved.getId()).executeUpdate())
                .isInstanceOf(RuntimeException.class);
    }
}
