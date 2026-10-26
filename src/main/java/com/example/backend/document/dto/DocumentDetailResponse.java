package com.example.backend.document.dto;

import com.example.backend.common.type.DocumentStatus;
import com.example.backend.common.util.DateTimes;
import com.example.backend.document.entity.Document;
import com.example.backend.ocr.dto.RunDetail;

import java.time.OffsetDateTime;

/** 문서 상세 (7). 프론트 폴링 대상. run 이 아직 없으면 latestRun 은 null. */
public record DocumentDetailResponse(
        Long documentId,
        String originalName,
        Long fileSize,
        Integer pageCount,
        DocumentStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        RunDetail latestRun
) {
    public static DocumentDetailResponse of(Document d, RunDetail latestRun) {
        return new DocumentDetailResponse(d.getId(), d.getOriginalName(), d.getFileSize(), d.getPageCount(),
                d.getStatus(), DateTimes.toKst(d.getCreatedAt()), DateTimes.toKst(d.getUpdatedAt()), latestRun);
    }
}
