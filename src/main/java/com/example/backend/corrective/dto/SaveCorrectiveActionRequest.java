package com.example.backend.corrective.dto;

import com.example.backend.corrective.entity.ActionSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 개선조치 등록·수정. 출처(sourceType·sourceId)는 등록 때만 쓰고 수정 때는 무시한다.
 * 출처: NEAR_MISS 아차사고 / INSPECTION 점검표 / AUDIT 감사보고서 / OTHER 기타(sourceId 없음).
 * 수정은 lockVersion 필수.
 */
public record SaveCorrectiveActionRequest(ActionSource sourceType, Long sourceId,
                                          @NotBlank @Size(max = 200) String title,
                                          @Size(max = 4000) String description,
                                          @NotNull Long assigneeId, @NotNull LocalDate dueDate,
                                          Integer lockVersion) {
}
