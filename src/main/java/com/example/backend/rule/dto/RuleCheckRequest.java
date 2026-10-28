package com.example.backend.rule.dto;

import com.example.backend.rule.entity.EvaluationTarget;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.rule.service.RuleCheckCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * check_rules 요청 본문 (웹·내부 API 공통, 규격 초안 3장).
 * workType·target.type 에 없는 값이 오면 본문을 읽지 못해 400 INVALID_REQUEST.
 */
public record RuleCheckRequest(
        @NotNull Long siteId,
        @NotNull WorkType workType,
        @Valid Target target,
        Long agentRunId,
        Map<String, Object> input
) {

    /** 판정을 저장할 문서. 없으면 미리 확인만 */
    public record Target(@NotNull EvaluationTarget type, @NotNull Long id) {
    }

    /** 에이전트(내부 API): agentRunId 그대로 */
    public RuleCheckCommand toCommand() {
        return new RuleCheckCommand(siteId, workType, target == null ? null : target.type(),
                target == null ? null : target.id(), agentRunId, input);
    }

    /** 웹: 에이전트 실행과 상관없으므로 agentRunId 는 받지 않는다 */
    public RuleCheckCommand toWebCommand() {
        return new RuleCheckCommand(siteId, workType, target == null ? null : target.type(),
                target == null ? null : target.id(), null, input);
    }
}
