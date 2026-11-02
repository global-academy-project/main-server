package com.example.backend.worker.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.worker.dto.AssignmentRequest;
import com.example.backend.worker.dto.SiteWorkerRequest;
import com.example.backend.worker.dto.SiteWorkerResponse;
import com.example.backend.worker.service.SiteWorkerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 현장 작업자 (TBM 참석자 선택 등). 목록은 현장 권한, 등록·수정·배치·떠남은 현장 관리자 이상 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/workers")
public class SiteWorkerController {

    private final SiteWorkerService siteWorkerService;

    public SiteWorkerController(SiteWorkerService siteWorkerService) {
        this.siteWorkerService = siteWorkerService;
    }

    @GetMapping
    public ApiResponse<List<SiteWorkerResponse>> list(@AuthenticationPrincipal Long userId, @PathVariable Long siteId) {
        return ApiResponse.ok(siteWorkerService.list(userId, siteId));
    }

    /** 새 작업자 등록 + 이 현장 배치 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SiteWorkerResponse> create(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                                  @Valid @RequestBody SiteWorkerRequest request) {
        return ApiResponse.ok(siteWorkerService.create(userId, siteId, request));
    }

    @PutMapping("/{workerId}")
    public ApiResponse<SiteWorkerResponse> update(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                                  @PathVariable Long workerId, @Valid @RequestBody SiteWorkerRequest request) {
        return ApiResponse.ok(siteWorkerService.update(userId, siteId, workerId, request));
    }

    /** 이미 등록된 작업자를 이 현장에 배치 (예전에 떠났으면 다시 배치) */
    @PostMapping("/{workerId}/assign")
    public ApiResponse<SiteWorkerResponse> assign(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                                  @PathVariable Long workerId,
                                                  @RequestBody(required = false) AssignmentRequest request) {
        return ApiResponse.ok(siteWorkerService.assign(userId, siteId, workerId, request == null ? null : request.date()));
    }

    /** 이 현장을 떠남 */
    @PostMapping("/{workerId}/leave")
    public ApiResponse<Void> leave(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                   @PathVariable Long workerId, @RequestBody(required = false) AssignmentRequest request) {
        siteWorkerService.leave(userId, siteId, workerId, request == null ? null : request.date());
        return ApiResponse.ok(null);
    }
}
