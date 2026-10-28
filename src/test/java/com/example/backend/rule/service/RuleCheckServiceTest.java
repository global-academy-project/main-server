package com.example.backend.rule.service;

import com.example.backend.common.exception.ErrorCode;
import com.example.backend.rule.entity.EvaluationResult;
import com.example.backend.rule.entity.EvaluationTarget;
import com.example.backend.rule.entity.RuleEvaluation;
import com.example.backend.rule.entity.RuleSeverity;
import com.example.backend.rule.entity.RuleViolation;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.rule.repository.RuleEvaluationRepository;
import com.example.backend.rule.repository.RuleRepository;
import com.example.backend.rule.repository.RuleViolationRepository;
import com.example.backend.site.entity.Site;
import com.example.backend.site.repository.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.example.backend.rule.service.RuleFixtures.lt;
import static com.example.backend.rule.service.RuleFixtures.o2Range;
import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RuleCheckService: 판정 + 기록 저장")
class RuleCheckServiceTest {

    private static final Map<String, Object> LOW_O2 =
            Map.of("measurement", Map.of("O2", 17.5, "CO2", 0.8, "CO", 5, "H2S", 0));

    private RuleRepository ruleRepository;
    private RuleEvaluationRepository evaluationRepository;
    private RuleViolationRepository violationRepository;
    private SiteRepository siteRepository;
    private RuleTargetLookup targetLookup;
    private RuleCheckService service;

    @BeforeEach
    void setUp() {
        ruleRepository = mock(RuleRepository.class);
        evaluationRepository = mock(RuleEvaluationRepository.class);
        violationRepository = mock(RuleViolationRepository.class);
        siteRepository = mock(SiteRepository.class);
        targetLookup = mock(RuleTargetLookup.class);
        JsonMapper jsonMapper = JsonMapper.builder().build();
        Clock clock = Clock.fixed(Instant.parse("2026-10-28T01:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new RuleCheckService(ruleRepository, evaluationRepository, violationRepository, siteRepository,
                targetLookup, new RuleEvaluator(jsonMapper), jsonMapper, clock);

        when(siteRepository.findById(1L)).thenReturn(Optional.of(site(1L, true)));
        when(ruleRepository.findByEnabledTrueAndWorkTypeInOrderByRuleCodeAsc(List.of("CONFINED", "ALL"))).thenReturn(List.of(
                o2Range(),
                lt("CONFINED_CO2_MAX", "measurement.CO2", "1.500", "PCT"),
                lt("CONFINED_CO_MAX", "measurement.CO", "30.000", "PPM"),
                lt("CONFINED_H2S_MAX", "measurement.H2S", "10.000", "PPM")));
        when(evaluationRepository.saveAndFlush(any(RuleEvaluation.class))).thenAnswer(inv -> {
            RuleEvaluation e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", 88L);
            return e;
        });
    }

    private static Site site(Long id, boolean active) {
        Site s = BeanUtils.instantiateClass(Site.class);
        ReflectionTestUtils.setField(s, "id", id);
        ReflectionTestUtils.setField(s, "name", "울산");
        ReflectionTestUtils.setField(s, "active", active);
        return s;
    }

    @Test
    @DisplayName("대상 문서 없이 부르면 판정만 하고 저장하지 않는다 (evaluationId = null)")
    void preview_doesNotSave() {
        RuleCheckResult r = service.check(new RuleCheckCommand(1L, WorkType.CONFINED, null, null, null, LOW_O2));

        assertThat(r.evaluationId()).isNull();
        assertThat(r.result()).isEqualTo(EvaluationResult.BLOCK);
        assertThat(r.rulesApplied()).isEqualTo(4);
        assertThat(r.violations()).extracting(RuleCheckResult.Violation::ruleCode).containsExactly("CONFINED_O2_RANGE");
        verify(evaluationRepository, never()).saveAndFlush(any());
        verify(violationRepository, never()).saveAll(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("허가서를 대상으로 부르면 판정·위반을 저장하고 evaluationId 를 준다 (입력은 스냅샷으로)")
    void withTarget_savesEvaluationAndViolations() {
        when(targetLookup.targetExists(EvaluationTarget.PERMIT, 12L, 1L)).thenReturn(true);
        when(targetLookup.agentRunExists(345L, 1L)).thenReturn(true);

        RuleCheckResult r = service.check(new RuleCheckCommand(1L, WorkType.CONFINED, EvaluationTarget.PERMIT, 12L, 345L, LOW_O2));

        assertThat(r.evaluationId()).isEqualTo(88L);
        RuleCheckResult.Violation v = r.violations().get(0);
        assertThat(v.expected()).isEqualTo("18 이상 23.5 미만 (%)");
        assertThat(v.legalBasis()).isEqualTo("산업안전보건기준에 관한 규칙 제618조");
        assertThat(v.severity()).isEqualTo(RuleSeverity.BLOCK);

        ArgumentCaptor<RuleEvaluation> eval = ArgumentCaptor.forClass(RuleEvaluation.class);
        verify(evaluationRepository).saveAndFlush(eval.capture());
        assertThat(eval.getValue().getSiteId()).isEqualTo(1L);
        assertThat(eval.getValue().getTargetType()).isEqualTo(EvaluationTarget.PERMIT);
        assertThat(eval.getValue().getTargetId()).isEqualTo(12L);
        assertThat(eval.getValue().getAgentRunId()).isEqualTo(345L);
        assertThat(eval.getValue().getResult()).isEqualTo(EvaluationResult.BLOCK);
        assertThat(eval.getValue().getRulesApplied()).isEqualTo(4);
        assertThat(eval.getValue().getInputSnapshot()).contains("\"O2\":17.5");
        assertThat(eval.getValue().getEvaluatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 28, 10, 0));

        ArgumentCaptor<List<RuleViolation>> viols = ArgumentCaptor.forClass(List.class);
        verify(violationRepository).saveAll(viols.capture());
        assertThat(viols.getValue()).hasSize(1);
        RuleViolation saved = viols.getValue().get(0);
        assertThat(saved.getEvaluationId()).isEqualTo(88L);
        assertThat(saved.getActualValue()).isEqualTo("17.5");
        assertThat(saved.getExpected()).isEqualTo("18 이상 23.5 미만 (%)");
        assertThat(saved.getLegalBasis()).isEqualTo("산업안전보건기준에 관한 규칙 제618조");
    }

    @Test
    @DisplayName("통과여도 대상이 있으면 PASS 판정을 저장한다 (승인 차단 해제 근거)")
    void pass_isAlsoSaved() {
        when(targetLookup.targetExists(EvaluationTarget.PERMIT, 12L, 1L)).thenReturn(true);
        Map<String, Object> normal = Map.of("measurement", Map.of("O2", 20.9, "CO2", 0.04, "CO", 0, "H2S", 0));

        RuleCheckResult r = service.check(new RuleCheckCommand(1L, WorkType.CONFINED, EvaluationTarget.PERMIT, 12L, null, normal));

        assertThat(r.result()).isEqualTo(EvaluationResult.PASS);
        assertThat(r.evaluationId()).isEqualTo(88L);
        assertThat(r.violations()).isEmpty();
    }

    @Test
    @DisplayName("다른 현장 문서이거나 없는 문서면 NOT_FOUND")
    void targetNotInSite_notFound() {
        when(targetLookup.targetExists(EvaluationTarget.PERMIT, 99L, 1L)).thenReturn(false);

        expectCode(() -> service.check(new RuleCheckCommand(1L, WorkType.CONFINED, EvaluationTarget.PERMIT, 99L, null, LOW_O2)),
                ErrorCode.NOT_FOUND);
        verify(evaluationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("없거나 중지된 현장이면 SITE_NOT_FOUND")
    void inactiveSite() {
        when(siteRepository.findById(2L)).thenReturn(Optional.of(site(2L, false)));

        expectCode(() -> service.check(new RuleCheckCommand(2L, WorkType.CONFINED, null, null, null, LOW_O2)),
                ErrorCode.SITE_NOT_FOUND);
        expectCode(() -> service.check(new RuleCheckCommand(3L, WorkType.CONFINED, null, null, null, LOW_O2)),
                ErrorCode.SITE_NOT_FOUND);
    }

    @Test
    @DisplayName("target 의 type 과 id 중 하나만 오면 INVALID_REQUEST")
    void halfTarget_invalid() {
        expectCode(() -> service.check(new RuleCheckCommand(1L, WorkType.CONFINED, EvaluationTarget.PERMIT, null, null, LOW_O2)),
                ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("이 현장의 실행이 아닌 agentRunId 면 INVALID_REQUEST")
    void unknownAgentRun_invalid() {
        when(targetLookup.agentRunExists(777L, 1L)).thenReturn(false);

        expectCode(() -> service.check(new RuleCheckCommand(1L, WorkType.CONFINED, null, null, 777L, LOW_O2)),
                ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("요청한 작업 종류와 ALL 규칙을 함께 조회한다")
    void queriesWorkTypeAndAll() {
        when(ruleRepository.findByEnabledTrueAndWorkTypeInOrderByRuleCodeAsc(List.of("HOT", "ALL"))).thenReturn(List.of());

        RuleCheckResult r = service.check(new RuleCheckCommand(1L, WorkType.HOT, null, null, null, Map.of()));

        assertThat(r.result()).isEqualTo(EvaluationResult.PASS);
        assertThat(r.rulesApplied()).isZero();
        verify(ruleRepository).findByEnabledTrueAndWorkTypeInOrderByRuleCodeAsc(List.of("HOT", "ALL"));
    }
}
