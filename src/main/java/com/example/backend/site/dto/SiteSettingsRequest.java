package com.example.backend.site.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

/**
 * 현장 운영 설정 (현장 관리자 이상). 통째로 바꾼다.
 *  - tbmDeadline 은 dailyLogDeadline 보다 앞 ("HH:mm" 또는 "HH:mm:ss")
 *  - approvalDueMinutes: 승인 요청 후 이 시간(분)이 지나면 재알림 (5~1440). 이미 요청된 결재의 마감은 그대로
 *  - nearMissWarnDays: 같은 구역 아차사고를 며칠 전까지 경고에 쓸지 (1~365)
 */
public record SiteSettingsRequest(@NotNull LocalTime tbmDeadline, @NotNull LocalTime dailyLogDeadline,
                                  @NotNull @Min(5) @Max(1440) Integer approvalDueMinutes,
                                  @NotNull @Min(1) @Max(365) Integer nearMissWarnDays,
                                  @Size(max = 50) String industry, @Min(0) Integer employeeCount,
                                  @Size(max = 255) String address) {
}
