package com.example.backend.preview.controller;

import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.common.response.ApiResponse;
import com.example.backend.preview.dto.PreviewResponse;
import com.example.backend.preview.service.PreviewService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 문서 미리보기 (저장 안 함). 승인함 상세와 문서 화면에서 같은 응답을 쓴다 (계획서 7-5장) */
@RestController
@RequestMapping("/api/v1")
public class PreviewController {

    private final PreviewService previewService;

    public PreviewController(PreviewService previewService) {
        this.previewService = previewService;
    }

    /** 승인함 상세: 결재 대상 문서 */
    @GetMapping("/approvals/{approvalId}/preview")
    public ApiResponse<PreviewResponse> ofApproval(@AuthenticationPrincipal Long userId, @PathVariable Long approvalId) {
        return ApiResponse.ok(previewService.ofApproval(userId, approvalId));
    }

    /** 문서 화면: /previews/PERMIT/52, /previews/RISK/40, /previews/TBM/12 */
    @GetMapping("/previews/{targetType}/{targetId}")
    public ApiResponse<PreviewResponse> ofTarget(@AuthenticationPrincipal Long userId, @PathVariable ApprovalTarget targetType,
                                                 @PathVariable Long targetId) {
        return ApiResponse.ok(previewService.ofTarget(userId, targetType, targetId));
    }
}
