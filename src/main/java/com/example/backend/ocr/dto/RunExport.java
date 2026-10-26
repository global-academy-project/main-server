package com.example.backend.ocr.dto;

import com.example.backend.common.type.Engine;
import com.example.backend.common.type.NetworkMode;
import com.example.backend.common.type.RunStatus;
import com.example.backend.common.util.DateTimes;
import com.example.backend.ocr.entity.OcrPage;
import com.example.backend.ocr.entity.OcrRun;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** json 내보내기용: RunDetail 에서 images 를 제외한 구조, 봉투 없음 (명세 13). */
public record RunExport(
        Long runId,
        Engine engine,
        NetworkMode networkMode,
        RunStatus status,
        boolean extractImages,
        Integer totalMs,
        BigDecimal avgConfidence,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt,
        List<PageItem> pages
) {
    public static RunExport of(OcrRun r, List<OcrPage> pages) {
        return new RunExport(r.getId(), r.getEngine(), r.getNetworkMode(), r.getStatus(),
                r.isExtractImages(), r.getTotalMs(), r.getAvgConfidence(), r.getErrorMessage(),
                DateTimes.toKst(r.getCreatedAt()), DateTimes.toKst(r.getFinishedAt()),
                pages.stream().map(PageItem::from).toList());
    }
}
