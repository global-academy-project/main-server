package com.example.backend.risk.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.risk.dto.RiskAssessmentResponse;
import com.example.backend.risk.dto.SaveRiskAssessmentRequest;
import com.example.backend.risk.service.RiskAssessmentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 일일 위험성평가 조회·작성·수정·새 판·승인 요청 */
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

    /** 작업의 일일 위험성평가 작성 (본문 siteId·workId, 작업에 아직 없을 때만) */
    @PostMapping("/risk-assessments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RiskAssessmentResponse> create(@AuthenticationPrincipal Long userId,
                                                      @Valid @RequestBody SaveRiskAssessmentRequest request) {
        return ApiResponse.ok(riskService.create(userId, request));
    }

    /** 작성 중 수정 (lockVersion 필수, 위험요인 통째로) */
    @PutMapping("/risk-assessments/{riskId}")
    public ApiResponse<RiskAssessmentResponse> update(@AuthenticationPrincipal Long userId, @PathVariable Long riskId,
                                                      @Valid @RequestBody SaveRiskAssessmentRequest request) {
        return ApiResponse.ok(riskService.update(userId, riskId, request));
    }

    /** 반려된 판에서 새 판 */
    @PostMapping("/risk-assessments/{riskId}/revise")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RiskAssessmentResponse> revise(@AuthenticationPrincipal Long userId, @PathVariable Long riskId) {
        return ApiResponse.ok(riskService.revise(userId, riskId));
    }

    @PostMapping("/risk-assessments/{riskId}/submit")
    public ApiResponse<RiskAssessmentResponse> submit(@AuthenticationPrincipal Long userId, @PathVariable Long riskId) {
        return ApiResponse.ok(riskService.submit(userId, riskId));
    }
}
