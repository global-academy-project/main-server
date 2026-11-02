package com.example.backend.site.dto;

import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMode;

import java.time.LocalTime;

/** 현장 정보와 운영 설정 (마감 시각·승인 재알림·아차사고 경고 기간) */
public record SiteSettingsResponse(Long siteId, String name, SiteMode mode, String industry, Integer employeeCount,
                                   String address, LocalTime tbmDeadline, LocalTime dailyLogDeadline,
                                   int approvalDueMinutes, int nearMissWarnDays, boolean active) {

    public static SiteSettingsResponse from(Site s) {
        return new SiteSettingsResponse(s.getId(), s.getName(), s.getMode(), s.getIndustry(), s.getEmployeeCount(),
                s.getAddress(), s.getTbmDeadline(), s.getDailyLogDeadline(), s.getApprovalDueMinutes(),
                s.getNearMissWarnDays(), s.isActive());
    }
}
