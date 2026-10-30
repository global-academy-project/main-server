package com.example.backend.dashboard.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.dashboard.dto.DashboardResponse;
import com.example.backend.dashboard.dto.DashboardSummary;
import com.example.backend.dashboard.service.DashboardService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** 대시보드 미완료 항목 (계획서 7-5장). 날짜는 작업일, 비우면 오늘 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** 그날 현황: ?date=2026-11-02 */
    @GetMapping
    public ApiResponse<DashboardResponse> day(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(dashboardService.day(userId, siteId, date));
    }

    /** 기간 날짜별 건수: ?from=2026-11-02&to=2026-11-06 */
    @GetMapping("/summary")
    public ApiResponse<DashboardSummary> summary(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(dashboardService.summary(userId, siteId, from, to));
    }
}
