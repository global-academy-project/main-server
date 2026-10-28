package com.example.backend.rule.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.rule.entity.Rule;
import com.example.backend.rule.entity.RuleEvaluation;
import com.example.backend.rule.entity.RuleViolation;
import com.example.backend.rule.repository.RuleEvaluationRepository;
import com.example.backend.rule.repository.RuleRepository;
import com.example.backend.rule.repository.RuleViolationRepository;
import com.example.backend.site.entity.Site;
import com.example.backend.site.repository.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * check_rules: 규칙 판정 + (대상 문서가 있으면) 판정 기록 저장.
 * 웹(/api/v1/rules/check)과 에이전트 내부 API 가 모두 여기를 부른다. 현장 권한 확인은 부르는 쪽(컨트롤러) 몫.
 *
 * 규칙 위반은 오류가 아니다 → 정상 응답에 result = BLOCK 으로 담는다.
 * 승인 요청을 막을 때는 저장된 최신 판정을 본다 (미리 확인 결과로 승인을 열지 않는다).
 */
@Service
public class RuleCheckService {

    /** rule_violations.actual_value / expected 길이 */
    private static final int VALUE_MAX = 100;

    private final RuleRepository ruleRepository;
    private final RuleEvaluationRepository evaluationRepository;
    private final RuleViolationRepository violationRepository;
    private final SiteRepository siteRepository;
    private final RuleTargetLookup targetLookup;
    private final RuleEvaluator evaluator;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public RuleCheckService(RuleRepository ruleRepository, RuleEvaluationRepository evaluationRepository,
                            RuleViolationRepository violationRepository, SiteRepository siteRepository,
                            RuleTargetLookup targetLookup, RuleEvaluator evaluator, JsonMapper jsonMapper,
                            Clock clock) {
        this.ruleRepository = ruleRepository;
        this.evaluationRepository = evaluationRepository;
        this.violationRepository = violationRepository;
        this.siteRepository = siteRepository;
        this.targetLookup = targetLookup;
        this.evaluator = evaluator;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional
    public RuleCheckResult check(RuleCheckCommand command) {
        validate(command);

        List<Rule> rules = ruleRepository.findByEnabledTrueAndWorkTypeInOrderByRuleCodeAsc(
                List.of(command.workType().name(), "ALL"));
        Map<String, Object> input = command.input() == null ? Map.of() : command.input();
        Judgement judgement = evaluator.evaluate(rules, input);

        if (command.targetType() == null) {
            return RuleCheckResult.of(null, judgement);   // 미리 확인: 저장하지 않음
        }

        RuleEvaluation evaluation = evaluationRepository.saveAndFlush(new RuleEvaluation(
                command.siteId(), command.targetType(), command.targetId(), judgement.result(),
                jsonMapper.writeValueAsString(input), judgement.rulesApplied(), command.agentRunId(),
                LocalDateTime.now(clock)));
        violationRepository.saveAll(judgement.findings().stream()
                .map(f -> new RuleViolation(evaluation.getId(), f.rule().getId(), cut(f.actual()), cut(f.expected()),
                        f.severity(), f.message(), f.rule().getLegalBasis()))
                .toList());
        return RuleCheckResult.of(evaluation.getId(), judgement);
    }

    private void validate(RuleCheckCommand c) {
        if (c.siteId() == null || c.workType() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "siteId 와 workType 은 필수입니다.");
        }
        if ((c.targetType() == null) != (c.targetId() == null)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "target 은 type 과 id 를 함께 보내야 합니다.");
        }
        Site site = siteRepository.findById(c.siteId()).orElse(null);
        if (site == null || !site.isActive()) {
            throw new BusinessException(ErrorCode.SITE_NOT_FOUND);
        }
        if (c.targetType() != null && !targetLookup.targetExists(c.targetType(), c.targetId(), c.siteId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "판정할 문서를 찾을 수 없습니다.");
        }
        if (c.agentRunId() != null && !targetLookup.agentRunExists(c.agentRunId(), c.siteId())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "agentRunId 에 해당하는 이 현장의 실행이 없습니다.");
        }
    }

    private static String cut(String s) {
        return s == null || s.length() <= VALUE_MAX ? s : s.substring(0, VALUE_MAX);
    }
}
