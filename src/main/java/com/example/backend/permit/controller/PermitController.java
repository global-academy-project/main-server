package com.example.backend.permit.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.permit.dto.AddMeasurementsRequest;
import com.example.backend.permit.dto.CreatePermitRequest;
import com.example.backend.permit.dto.PermitCheckResponse;
import com.example.backend.permit.dto.PermitResponse;
import com.example.backend.permit.dto.UpdatePermitRequest;
import com.example.backend.permit.service.PermitService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 작업허가 (화기·밀폐·고소). 로그인 + 그 현장 권한 필요.
 * 흐름: 작성 → 측정값 → 판정(check) → 승인 요청(submit)
 */
@RestController
@RequestMapping("/api/v1/permits")
public class PermitController {

    private final PermitService permitService;

    public PermitController(PermitService permitService) {
        this.permitService = permitService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PermitResponse> create(@AuthenticationPrincipal Long userId,
                                              @Valid @RequestBody CreatePermitRequest request) {
        return ApiResponse.ok(permitService.create(userId, request));
    }

    @GetMapping("/{permitId}")
    public ApiResponse<PermitResponse> get(@AuthenticationPrincipal Long userId, @PathVariable Long permitId) {
        return ApiResponse.ok(permitService.get(userId, permitId));
    }

    /** 내용 전체 교체 (작성 중·차단 상태). 판정은 다시 해야 한다 */
    @PutMapping("/{permitId}")
    public ApiResponse<PermitResponse> update(@AuthenticationPrincipal Long userId, @PathVariable Long permitId,
                                              @Valid @RequestBody UpdatePermitRequest request) {
        return ApiResponse.ok(permitService.update(userId, permitId, request));
    }

    @PostMapping("/{permitId}/measurements")
    public ApiResponse<PermitResponse> addMeasurements(@AuthenticationPrincipal Long userId, @PathVariable Long permitId,
                                                       @Valid @RequestBody AddMeasurementsRequest request) {
        return ApiResponse.ok(permitService.addMeasurements(userId, permitId, request));
    }

    /** 규칙 판정 (판정 기록 저장). 위반은 오류가 아니라 data.evaluation.result = BLOCK */
    @PostMapping("/{permitId}/check")
    public ApiResponse<PermitCheckResponse> check(@AuthenticationPrincipal Long userId, @PathVariable Long permitId) {
        return ApiResponse.ok(permitService.check(userId, permitId));
    }

    /** 승인 요청. 최근 판정이 없거나 BLOCK 이면 409 INVALID_STATE */
    @PostMapping("/{permitId}/submit")
    public ApiResponse<PermitResponse> submit(@AuthenticationPrincipal Long userId, @PathVariable Long permitId) {
        return ApiResponse.ok(permitService.submit(userId, permitId));
    }
}
