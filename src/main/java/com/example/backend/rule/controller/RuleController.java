package com.example.backend.rule.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.rule.dto.RuleCheckRequest;
import com.example.backend.rule.service.RuleCheckResult;
import com.example.backend.rule.service.RuleCheckService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 웹 화면용 규칙 판정 (허가서 작성 화면). 로그인 + 그 현장 권한이 필요하다.
 * 규칙 위반은 오류가 아니다 → success: true, data.result = BLOCK
 */
@RestController
@RequestMapping("/api/v1/rules")
public class RuleController {

    private final RuleCheckService ruleCheckService;

    public RuleController(RuleCheckService ruleCheckService) {
        this.ruleCheckService = ruleCheckService;
    }

    @PostMapping("/check")
    public ApiResponse<RuleCheckResult> check(@AuthenticationPrincipal Long userId,
                                              @Valid @RequestBody RuleCheckRequest request) {
        return ApiResponse.ok(ruleCheckService.checkForUser(userId, request.toWebCommand()));
    }
}
