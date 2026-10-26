package com.example.backend.ocr.dto;

import com.example.backend.common.type.PageSource;
import com.example.backend.ocr.entity.OcrPage;

import java.math.BigDecimal;

public record PageItem(int pageNo, PageSource source, String text, BigDecimal confidence, Integer processingMs) {

    public static PageItem from(OcrPage p) {
        return new PageItem(p.getPageNo(), p.getSource(), p.getText(), p.getConfidence(), p.getProcessingMs());
    }
}
