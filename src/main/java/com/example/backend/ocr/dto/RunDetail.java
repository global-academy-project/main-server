package com.example.backend.ocr.dto;

import com.example.backend.common.type.Engine;
import com.example.backend.common.type.NetworkMode;
import com.example.backend.common.type.RunStatus;
import com.example.backend.common.util.DateTimes;
import com.example.backend.ocr.entity.OcrImage;
import com.example.backend.ocr.entity.OcrPage;
import com.example.backend.ocr.entity.OcrRun;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** RunSummary + extractImages, pages[], images[] (명세 §3 RunDetail). */
public record RunDetail(
        Long runId,
        Engine engine,
        NetworkMode networkMode,
        RunStatus status,
        boolean extractImages,
        Integer totalMs,
        BigDecimal avgConfidence,
        String errorMessage,
        String summary,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt,
        List<PageItem> pages,
        List<ImageItem> images
) {
    public static RunDetail of(OcrRun r, List<OcrPage> pages, List<OcrImage> images) {
        return new RunDetail(r.getId(), r.getEngine(), r.getNetworkMode(), r.getStatus(),
                r.isExtractImages(), r.getTotalMs(), r.getAvgConfidence(), r.getErrorMessage(),
                r.getSummary(),
                DateTimes.toKst(r.getCreatedAt()), DateTimes.toKst(r.getFinishedAt()),
                pages.stream().map(PageItem::from).toList(),
                images.stream().map(i -> ImageItem.of(r.getDocumentId(), r.getId(), i)).toList());
    }
}
