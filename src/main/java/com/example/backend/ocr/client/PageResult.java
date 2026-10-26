package com.example.backend.ocr.client;

import com.example.backend.common.type.PageSource;

import java.math.BigDecimal;

public record PageResult(int pageNo, PageSource source, String text, BigDecimal confidence, Integer processingMs) {
}
