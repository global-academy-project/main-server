package com.example.backend.ocr.client;

import com.example.backend.common.type.Engine;
import com.example.backend.common.type.NetworkMode;

import java.math.BigDecimal;
import java.util.List;

/** FastAPI POST /ocr/extract (F3) 응답에 대응. engine 은 실제 사용된 주 엔진. */
public record OcrResult(
        Engine engine,
        NetworkMode networkMode,
        int pageCount,
        int totalMs,
        BigDecimal avgConfidence,
        List<PageResult> pages,
        List<ImageResult> images,
        String summary
) {
}
