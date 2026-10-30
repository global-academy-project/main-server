package com.example.backend.dashboard.dto;

import com.example.backend.work.entity.WorkStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * GET /api/v1/sites/{siteId}/dashboard?date= : 그날 현황과 미완료 항목.
 *  - works: 그날 작업 수 (취소 제외). BLOCKED 는 전체에 포함하고 blocked 로 따로
 *  - teams: 팀별 (팀 없는 작업은 teamId null, "팀 미지정")
 *  - items: 미완료 항목 9종 (IncompleteType 순서, 0건 포함). overdue 는 마감 지난 것이 하나라도 있으면 true
 */
public record DashboardResponse(Long siteId, LocalDate date, LocalDateTime now, Deadlines deadlines, Works works,
                                List<Team> teams, List<Item> items) {

    /** 그날 마감 시각과 지났는지 */
    public record Deadlines(LocalDateTime tbmDeadline, boolean tbmPassed, LocalDateTime dailyLogDeadline,
                            boolean dailyLogPassed, int approvalDueMinutes) {
    }

    public record Works(int total, int blocked, Map<WorkStatus, Integer> byStatus) {
    }

    public record Team(Long teamId, String teamName, int works, int blocked, int incomplete) {
    }

    public record Item(IncompleteType type, String label, int count, boolean overdue, List<Entry> entries) {
    }

    /**
     * 미완료 한 건. targetType·targetId 로 화면 이동 (안전일지 미작성은 기록이 없어 targetId null).
     * detail: 빠진 내용 (예: "LEL", "서명 2/3"), dueAt: 마감·기한
     */
    public record Entry(String targetType, Long targetId, String title, Long workId, String detail, LocalDateTime dueAt,
                        boolean overdue) {
    }
}
