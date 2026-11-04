package com.example.backend.dailylog.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.dailylog.dto.DailyLogDraftRequest;
import com.example.backend.dailylog.dto.DailyLogListItem;
import com.example.backend.dailylog.dto.DailyLogResponse;
import com.example.backend.dailylog.dto.SubmitDailyLogRequest;
import com.example.backend.dailylog.dto.UpdateDailyLogRequest;
import com.example.backend.dailylog.service.DailyLogService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** 일일 안전일지: 목록, 초안 생성(서버 집계), 상세, 요약 수정, 승인 요청. 승인·반려·회수는 결재 API (DAILY_LOG) */
@RestController
@RequestMapping("/api/v1")
public class DailyLogController {

    private final DailyLogService service;

    public DailyLogController(DailyLogService service) {
        this.service = service;
    }

    @GetMapping("/sites/{siteId}/daily-safety-logs")
    public ApiResponse<List<DailyLogListItem>> list(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.list(userId, siteId, from, to));
    }

    @PostMapping("/sites/{siteId}/daily-safety-logs/draft")
    public ApiResponse<DailyLogResponse> draft(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                               @Valid @RequestBody DailyLogDraftRequest request) {
        return ApiResponse.ok(service.draft(userId, siteId, request.date()));
    }

    @GetMapping("/daily-safety-logs/{dailyLogId}")
    public ApiResponse<DailyLogResponse> get(@AuthenticationPrincipal Long userId, @PathVariable Long dailyLogId) {
        return ApiResponse.ok(service.get(userId, dailyLogId));
    }

    @PutMapping("/daily-safety-logs/{dailyLogId}")
    public ApiResponse<DailyLogResponse> update(@AuthenticationPrincipal Long userId, @PathVariable Long dailyLogId,
                                                @Valid @RequestBody UpdateDailyLogRequest request) {
        return ApiResponse.ok(service.update(userId, dailyLogId, request));
    }

    @PostMapping("/daily-safety-logs/{dailyLogId}/submit")
    public ApiResponse<DailyLogResponse> submit(@AuthenticationPrincipal Long userId, @PathVariable Long dailyLogId,
                                                @Valid @RequestBody SubmitDailyLogRequest request) {
        return ApiResponse.ok(service.submit(userId, dailyLogId, request.lockVersion()));
    }
}
