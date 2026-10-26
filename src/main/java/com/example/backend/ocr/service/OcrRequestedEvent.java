package com.example.backend.ocr.service;

/** 업로드/재처리 트랜잭션이 커밋된 뒤 OCR 처리를 시작하라는 이벤트. */
public record OcrRequestedEvent(Long runId, Long documentId) {
}
