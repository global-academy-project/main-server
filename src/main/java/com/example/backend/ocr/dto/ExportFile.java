package com.example.backend.ocr.dto;

import org.springframework.http.MediaType;

/** 내보내기 결과: 다운로드 파일명, 콘텐츠 타입, 본문(txt 는 String, json 은 RunExport). */
public record ExportFile(String fileName, MediaType mediaType, Object body) {
}
