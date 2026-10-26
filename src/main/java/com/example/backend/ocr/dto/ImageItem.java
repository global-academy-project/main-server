package com.example.backend.ocr.dto;

import com.example.backend.ocr.entity.OcrImage;

public record ImageItem(Long imageId, int pageNo, String format, Integer width, Integer height, String url) {

    public static ImageItem of(Long documentId, Long runId, OcrImage i) {
        String url = "/api/v1/documents/" + documentId + "/runs/" + runId + "/images/" + i.getId();
        return new ImageItem(i.getId(), i.getPageNo(), i.getFormat(), i.getWidth(), i.getHeight(), url);
    }
}
