package com.example.backend.risk.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.risk.dto.RiskAssessmentResponse;
import com.example.backend.risk.service.RiskAssessmentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 위험성평가 조회·승인 요청 (작성·수정 API 는 다음) */
@RestController
@RequestMapping("/api/v1")
public class RiskAssessmentController {

    private final RiskAssessmentService riskService;

    public RiskAssessmentController(RiskAssessmentService riskService) {
        this.riskService = riskService;
    }

    @GetMapping("/risk-assessments/{riskId}")
    public ApiResponse<RiskAssessmentResponse> get(@AuthenticationPrincipal Long userId, @PathVariable Long riskId) {
        return ApiResponse.ok(riskService.get(userId, riskId));
    }

    /** 작업의 최신 판 위험성평가 (에이전트 초안 확인) */
    @GetMapping("/works/{workId}/risk-assessment")
    public ApiResponse<RiskAssessmentResponse> getByWork(@AuthenticationPrincipal Long userId, @PathVariable Long workId) {
        return ApiResponse.ok(riskService.getByWork(userId, workId));
    }

    @PostMapping("/risk-assessments/{riskId}/submit")
    public ApiResponse<RiskAssessmentResponse> submit(@AuthenticationPrincipal Long userId, @PathVariable Long riskId) {
        return ApiResponse.ok(riskService.submit(userId, riskId));
    }
}
