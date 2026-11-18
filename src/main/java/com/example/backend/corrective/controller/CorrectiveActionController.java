package com.example.backend.corrective.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.corrective.dto.AssigneeResponse;
import com.example.backend.corrective.dto.CorrectiveActionResponse;
import com.example.backend.corrective.dto.CorrectiveActionStepRequest;
import com.example.backend.corrective.dto.SaveCorrectiveActionRequest;
import com.example.backend.corrective.entity.ActionSource;
import com.example.backend.corrective.entity.ActionStatus;
import com.example.backend.corrective.service.CorrectiveActionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 개선조치: 목록·담당자 후보·등록·수정·시작·완료·확인·미흡·취소 */
@RestController
@RequestMapping("/api/v1")
public class CorrectiveActionController {

    private final CorrectiveActionService service;

    public CorrectiveActionController(CorrectiveActionService service) {
        this.service = service;
    }

    /** ?status=OPEN&status=IN_PROGRESS&sourceType=NEAR_MISS&sourceId=9 (모두 생략 가능) */
    @GetMapping("/sites/{siteId}/corrective-actions")
    public ApiResponse<List<CorrectiveActionResponse>> list(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                                            @RequestParam(required = false) List<ActionStatus> status,
                                                            @RequestParam(required = false) ActionSource sourceType,
                                                            @RequestParam(required = false) Long sourceId) {
        return ApiResponse.ok(service.list(userId, siteId, status, sourceType, sourceId));
    }

    @GetMapping("/sites/{siteId}/corrective-actions/assignees")
    public ApiResponse<List<AssigneeResponse>> assignees(@AuthenticationPrincipal Long userId, @PathVariable Long siteId) {
        return ApiResponse.ok(service.assignees(userId, siteId));
    }

    @PostMapping("/sites/{siteId}/corrective-actions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CorrectiveActionResponse> create(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                                        @Valid @RequestBody SaveCorrectiveActionRequest request) {
        return ApiResponse.ok(service.create(userId, siteId, request));
    }

    @GetMapping("/corrective-actions/{actionId}")
    public ApiResponse<CorrectiveActionResponse> get(@AuthenticationPrincipal Long userId, @PathVariable Long actionId) {
        return ApiResponse.ok(service.get(userId, actionId));
    }

    /** 내용·담당·기한 수정 (끝나기 전, lockVersion 필수) */
    @PutMapping("/corrective-actions/{actionId}")
    public ApiResponse<CorrectiveActionResponse> update(@AuthenticationPrincipal Long userId, @PathVariable Long actionId,
                                                        @Valid @RequestBody SaveCorrectiveActionRequest request) {
        return ApiResponse.ok(service.update(userId, actionId, request));
    }

    @PostMapping("/corrective-actions/{actionId}/start")
    public ApiResponse<CorrectiveActionResponse> start(@AuthenticationPrincipal Long userId, @PathVariable Long actionId,
                                                       @Valid @RequestBody CorrectiveActionStepRequest request) {
        return ApiResponse.ok(service.start(userId, actionId, request));
    }

    /** 완료: note(조치 내용) 필수 */
    @PostMapping("/corrective-actions/{actionId}/complete")
    public ApiResponse<CorrectiveActionResponse> complete(@AuthenticationPrincipal Long userId, @PathVariable Long actionId,
                                                          @Valid @RequestBody CorrectiveActionStepRequest request) {
        return ApiResponse.ok(service.complete(userId, actionId, request));
    }

    @PostMapping("/corrective-actions/{actionId}/verify")
    public ApiResponse<CorrectiveActionResponse> verify(@AuthenticationPrincipal Long userId, @PathVariable Long actionId,
                                                        @Valid @RequestBody CorrectiveActionStepRequest request) {
        return ApiResponse.ok(service.verify(userId, actionId, request));
    }

    /** 확인에서 미흡 → 다시 조치 중 (note: 사유) */
    @PostMapping("/corrective-actions/{actionId}/reopen")
    public ApiResponse<CorrectiveActionResponse> reopen(@AuthenticationPrincipal Long userId, @PathVariable Long actionId,
                                                        @Valid @RequestBody CorrectiveActionStepRequest request) {
        return ApiResponse.ok(service.reopen(userId, actionId, request));
    }

    @PostMapping("/corrective-actions/{actionId}/cancel")
    public ApiResponse<CorrectiveActionResponse> cancel(@AuthenticationPrincipal Long userId, @PathVariable Long actionId,
                                                        @Valid @RequestBody CorrectiveActionStepRequest request) {
        return ApiResponse.ok(service.cancel(userId, actionId, request));
    }
}
