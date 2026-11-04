package com.example.backend.dailylog.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * PUT /api/v1/daily-safety-logs/{id}. 요약만 고친다 (작성 중일 때만).
 * stats·checks·evidence 는 서버 집계라 받지 않는다. 바꾸려면 초안 생성(draft)을 다시 부른다.
 * summary 를 비우면 요약 없음 (승인 요청 전에 다시 채워야 함).
 */
public record UpdateDailyLogRequest(@NotNull Integer lockVersion, @Size(max = 4000) String summary) {
}
