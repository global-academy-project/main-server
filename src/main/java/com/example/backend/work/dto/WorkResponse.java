package com.example.backend.work.dto;

import com.example.backend.rule.entity.WorkType;
import com.example.backend.work.entity.Work;
import com.example.backend.work.entity.WorkStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record WorkResponse(Long workId, Long siteId, LocalDate workDate, String title, String description,
                           WorkType workType, boolean highRisk, LocalDateTime startAt, LocalDateTime endAt,
                           Integer workerCount, WorkStatus status, Long writerId) {

    public static WorkResponse from(Work w) {
        return new WorkResponse(w.getId(), w.getSiteId(), w.getWorkDate(), w.getTitle(), w.getDescription(),
                w.getWorkType(), w.isHighRisk(), w.getStartAt(), w.getEndAt(), w.getWorkerCount(),
                w.getStatus(), w.getWriterId());
    }
}
