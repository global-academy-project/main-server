package com.example.backend.work.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.work.dto.CreateWorkRequest;
import com.example.backend.work.dto.WorkListItem;
import com.example.backend.work.dto.WorkResponse;
import com.example.backend.work.service.WorkService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class WorkController {

    private final WorkService workService;

    public WorkController(WorkService workService) {
        this.workService = workService;
    }

    @PostMapping("/works")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkResponse> create(@AuthenticationPrincipal Long userId,
                                            @Valid @RequestBody CreateWorkRequest request) {
        return ApiResponse.ok(workService.create(userId, request));
    }

    @GetMapping("/works/{workId}")
    public ApiResponse<WorkResponse> get(@AuthenticationPrincipal Long userId, @PathVariable Long workId) {
        return ApiResponse.ok(workService.get(userId, workId));
    }

    /** 현장 작업 목록: ?from=2026-11-02&to=2026-11-06 (비우면 오늘). 허가서·위험성평가·TBM 상태 포함 */
    @GetMapping("/sites/{siteId}/works")
    public ApiResponse<List<WorkListItem>> list(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(workService.list(userId, siteId, from, to));
    }
}
