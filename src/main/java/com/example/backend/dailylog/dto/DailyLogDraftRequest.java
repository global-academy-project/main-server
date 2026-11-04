package com.example.backend.dailylog.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** POST /api/v1/sites/{siteId}/daily-safety-logs/draft. 그날 기록을 서버가 집계해 초안을 만든다 (DRAFT 면 다시 집계해 덮어씀) */
public record DailyLogDraftRequest(@NotNull LocalDate date) {
}
