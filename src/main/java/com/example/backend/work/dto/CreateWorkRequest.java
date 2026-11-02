package com.example.backend.work.dto;

import com.example.backend.rule.entity.WorkType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** POST /api/v1/works (최소 작업 등록. 허가서를 붙이기 위한 용도). zoneId 는 선택 (같은 현장의 사용 중 구역) */
public record CreateWorkRequest(
        @NotNull Long siteId,
        @NotNull LocalDate workDate,
        @NotBlank @Size(max = 200) String title,
        String description,
        @NotNull WorkType workType,
        LocalDateTime startAt,
        LocalDateTime endAt,
        @Min(1) Integer workerCount,
        Long zoneId
) {

    /** 구역 없이 등록 (에이전트 초안 저장 등) */
    public CreateWorkRequest(Long siteId, LocalDate workDate, String title, String description, WorkType workType,
                             LocalDateTime startAt, LocalDateTime endAt, Integer workerCount) {
        this(siteId, workDate, title, description, workType, startAt, endAt, workerCount, null);
    }
}
