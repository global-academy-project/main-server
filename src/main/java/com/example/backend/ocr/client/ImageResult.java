package com.example.backend.ocr.client;

/** 추출 이미지. data 는 디코딩된 PNG 바이트 (FastAPI 응답의 dataBase64 를 디코딩한 값). */
public record ImageResult(int pageNo, String format, int width, int height, byte[] data) {
}
