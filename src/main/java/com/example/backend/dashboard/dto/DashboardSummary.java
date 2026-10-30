package com.example.backend.dashboard.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * GET /api/v1/sites/{siteId}/dashboard/summary?from=&to= : 기간 날짜별 미완료 건수 (양 끝 포함, 작업일 기준).
 *  - days: 날짜별 작업 수·차단 수와 날짜 항목 건수 (CORRECTIVE_OPEN·APPROVAL_OVERDUE 는 날짜와 무관해 빠짐)
 *  - totals: 기간 합계
 *  - current: 지금 기준 현황 (개선조치 미완료·지연, 승인 지연)
 */
public record DashboardSummary(Long siteId, LocalDate from, LocalDate to, List<Day> days,
                               Map<IncompleteType, Integer> totals, Current current) {

    public record Day(LocalDate date, int works, int blocked, Map<IncompleteType, Integer> incomplete) {
    }

    public record Current(int correctiveOpen, int correctiveOverdue, int approvalOverdue) {
    }
}
