package com.example.backend.work.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.work.dto.AcknowledgeWarningRequest;
import com.example.backend.work.dto.WorkWarningResponse;
import com.example.backend.work.service.WorkWarningService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 작업 경고 (같은 구역 아차사고 등): 목록·확인 */
@RestController
@RequestMapping("/api/v1")
public class WorkWarningController {

    private final WorkWarningService service;

    public WorkWarningController(WorkWarningService service) {
        this.service = service;
    }

    @GetMapping("/works/{workId}/warnings")
    public ApiResponse<List<WorkWarningResponse>> list(@AuthenticationPrincipal Long userId, @PathVariable Long workId) {
        return ApiResponse.ok(service.list(userId, workId));
    }

    /** 본문: {"note": "안전대 2중 체결"} (긴급 경고는 필수). 본문 없이 보내도 된다 (일반 경고) */
    @PostMapping("/warnings/{warningId}/acknowledge")
    public ApiResponse<WorkWarningResponse> acknowledge(@AuthenticationPrincipal Long userId, @PathVariable Long warningId,
                                                        @Valid @RequestBody(required = false) AcknowledgeWarningRequest request) {
        return ApiResponse.ok(service.acknowledge(userId, warningId, request == null ? null : request.note()));
    }
}
