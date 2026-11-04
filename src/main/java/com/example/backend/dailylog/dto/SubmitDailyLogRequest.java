package com.example.backend.dailylog.dto;

import jakarta.validation.constraints.NotNull;

/** POST /api/v1/daily-safety-logs/{id}/submit. 화면이 본 버전과 다르면 409 */
public record SubmitDailyLogRequest(@NotNull Integer lockVersion) {
}
