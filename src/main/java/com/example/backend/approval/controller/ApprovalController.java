package com.example.backend.approval.controller;

import com.example.backend.approval.dto.*;
import com.example.backend.approval.service.ApprovalService;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 승인함·결재 (계획서 7-5장 승인 API). 승인 요청은 문서별 경로(POST /permits/{id}/submit 등)에서 한다.
 * 권한 없음 403 FORBIDDEN, 이미 결정·내 차례 아님 409 INVALID_STATE
 */
@RestController
@RequestMapping("/api/v1")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    /** 내 승인 대기 */
    @GetMapping("/sites/{siteId}/approvals/pending")
    public ApiResponse<List<ApprovalSummary>> pending(@AuthenticationPrincipal Long userId, @PathVariable Long siteId) {
        return ApiResponse.ok(approvalService.pending(userId, siteId));
    }

    /** 내가 요청한 문서: ?mine=requested */
    @GetMapping("/sites/{siteId}/approvals")
    public ApiResponse<List<ApprovalSummary>> list(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                                   @RequestParam String mine) {
        if (!"requested".equals(mine)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "mine 은 requested 만 지원합니다.");
        }
        return ApiResponse.ok(approvalService.requested(userId, siteId));
    }

    @GetMapping("/approvals/{approvalId}")
    public ApiResponse<ApprovalDetail> get(@AuthenticationPrincipal Long userId, @PathVariable Long approvalId) {
        return ApiResponse.ok(approvalService.get(userId, approvalId));
    }

    @PostMapping("/approvals/{approvalId}/approve")
    public ApiResponse<ApprovalDetail> approve(@AuthenticationPrincipal Long userId, @PathVariable Long approvalId,
                                               @Valid @RequestBody(required = false) ApproveRequest request) {
        return ApiResponse.ok(approvalService.approve(userId, approvalId, request == null ? null : request.comment()));
    }

    @PostMapping("/approvals/{approvalId}/reject")
    public ApiResponse<ApprovalDetail> reject(@AuthenticationPrincipal Long userId, @PathVariable Long approvalId,
                                              @Valid @RequestBody RejectRequest request) {
        return ApiResponse.ok(approvalService.reject(userId, approvalId, request.reason(), request.missingItems()));
    }

    @PostMapping("/approvals/{approvalId}/cancel")
    public ApiResponse<ApprovalDetail> cancel(@AuthenticationPrincipal Long userId, @PathVariable Long approvalId,
                                              @Valid @RequestBody(required = false) CancelRequest request) {
        return ApiResponse.ok(approvalService.cancel(userId, approvalId, request == null ? null : request.comment()));
    }
}
