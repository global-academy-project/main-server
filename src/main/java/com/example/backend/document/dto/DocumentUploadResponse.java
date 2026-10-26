package com.example.backend.document.dto;

import com.example.backend.common.type.DocumentStatus;
import com.example.backend.common.type.Engine;
import com.example.backend.common.type.NetworkMode;
import com.example.backend.common.util.DateTimes;
import com.example.backend.document.entity.Document;
import com.example.backend.ocr.entity.OcrRun;

import java.time.OffsetDateTime;

/** 업로드(5)·재처리(12) 공통 응답, 202. */
public record DocumentUploadResponse(
        Long documentId,
        Long runId,
        String originalName,
        Long fileSize,
        DocumentStatus status,
        Engine engine,
        NetworkMode networkMode,
        OffsetDateTime createdAt
) {
    public static DocumentUploadResponse of(Document d, OcrRun r) {
        return new DocumentUploadResponse(d.getId(), r.getId(), d.getOriginalName(), d.getFileSize(),
                d.getStatus(), r.getEngine(), r.getNetworkMode(), DateTimes.toKst(r.getCreatedAt()));
    }
}
