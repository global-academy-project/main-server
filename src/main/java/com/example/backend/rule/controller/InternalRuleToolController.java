package com.example.backend.rule.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.rule.dto.RuleCheckRequest;
import com.example.backend.rule.service.RuleCheckResult;
import com.example.backend.rule.service.RuleCheckService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 에이전트 도구 check_rules (AI 서버 → Spring). 인증은 InternalTokenFilter (X-Internal-Token).
 * 경로 규칙: POST /internal/v1/tools/{tool} ({tool} = AI 서버 도구 레지스트리 이름)
 * siteId·agentRunId 는 AI 서버 handler 가 실행 요청 값으로 채운다 (LLM 이 정하지 않음).
 */
@RestController
@RequestMapping("/internal/v1/tools")
public class InternalRuleToolController {

    private final RuleCheckService ruleCheckService;

    public InternalRuleToolController(RuleCheckService ruleCheckService) {
        this.ruleCheckService = ruleCheckService;
    }

    @PostMapping("/check_rules")
    public ApiResponse<RuleCheckResult> checkRules(@Valid @RequestBody RuleCheckRequest request) {
        return ApiResponse.ok(ruleCheckService.check(request.toCommand()));
    }
}
