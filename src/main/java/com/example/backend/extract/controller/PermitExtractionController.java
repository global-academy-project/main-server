package com.example.backend.extract.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.extract.dto.ApplyExtractionRequest;
import com.example.backend.extract.service.ExtractApplyService;
import com.example.backend.permit.dto.PermitResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 확인한 문장 추출값을 허가서에 넣기 (원문·추출값 스냅샷 포함). 다음에 POST /permits/{id}/check 로 정식 판정 */
@RestController
public class PermitExtractionController {

    private final ExtractApplyService service;

    public PermitExtractionController(ExtractApplyService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/permits/{permitId}/extraction")
    public ApiResponse<PermitResponse> apply(@AuthenticationPrincipal Long userId, @PathVariable Long permitId,
                                             @Valid @RequestBody ApplyExtractionRequest request) {
        return ApiResponse.ok(service.apply(userId, permitId, request));
    }
}
