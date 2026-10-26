package com.example.backend.document.dto;

import com.example.backend.common.type.DocumentStatus;
import com.example.backend.common.type.Engine;
import com.example.backend.common.util.DateTimes;
import com.example.backend.document.entity.Document;
import com.example.backend.ocr.entity.OcrRun;

import java.time.OffsetDateTime;

/** 목록 항목 (6). pageCount 는 처리 전이면 null, run 이 없으면 latestRunId/latestEngine 도 null. */
public record DocumentListItem(
        Long documentId,
        String originalName,
        Long fileSize,
        Integer pageCount,
        DocumentStatus status,
        Long latestRunId,
        Engine latestEngine,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static DocumentListItem of(Document d, OcrRun latest) {
        return new DocumentListItem(d.getId(), d.getOriginalName(), d.getFileSize(), d.getPageCount(),
                d.getStatus(), latest == null ? null : latest.getId(), latest == null ? null : latest.getEngine(),
                DateTimes.toKst(d.getCreatedAt()), DateTimes.toKst(d.getUpdatedAt()));
    }
}
