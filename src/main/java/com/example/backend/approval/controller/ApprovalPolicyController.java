package com.example.backend.approval.controller;

import com.example.backend.approval.dto.ApprovalLine;
import com.example.backend.approval.dto.ApprovalLine.StepRequest;
import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.service.ApprovalPolicyService;
import com.example.backend.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 현장 결재선 정책. 조회는 현장 권한, 변경은 현장 관리자 이상 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/approval-policies")
public class ApprovalPolicyController {

    private final ApprovalPolicyService service;

    public ApprovalPolicyController(ApprovalPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ApprovalLine>> list(@AuthenticationPrincipal Long userId, @PathVariable Long siteId) {
        return ApiResponse.ok(service.list(userId, siteId));
    }

    /** 본문: [{"label":"현장 승인","requiredRole":"APPROVER"}, ...]. 빈 배열이면 기본 1단계로 */
    @PutMapping("/{targetType}")
    public ApiResponse<ApprovalLine> replace(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                             @PathVariable ApprovalTarget targetType,
                                             @RequestBody List<@Valid @NotNull StepRequest> steps) {
        return ApiResponse.ok(service.replace(userId, siteId, targetType, steps));
    }
}
