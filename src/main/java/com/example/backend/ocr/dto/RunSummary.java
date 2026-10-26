package com.example.backend.ocr.dto;

import com.example.backend.common.type.Engine;
import com.example.backend.common.type.NetworkMode;
import com.example.backend.common.type.RunStatus;
import com.example.backend.common.util.DateTimes;
import com.example.backend.ocr.entity.OcrRun;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 실행 이력 한 건 (명세 §3 RunSummary). 진행 중이면 totalMs/avgConfidence/finishedAt 은 null. */
public record RunSummary(
        Long runId,
        Engine engine,
        NetworkMode networkMode,
        RunStatus status,
        Integer totalMs,
        BigDecimal avgConfidence,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt
) {
    public static RunSummary from(OcrRun r) {
        return new RunSummary(r.getId(), r.getEngine(), r.getNetworkMode(), r.getStatus(),
                r.getTotalMs(), r.getAvgConfidence(), r.getErrorMessage(),
                DateTimes.toKst(r.getCreatedAt()), DateTimes.toKst(r.getFinishedAt()));
    }
}
