package com.example.backend.document.dto;

import java.nio.file.Path;

/** 원본 PDF 다운로드용: 실제 경로와 사용자에게 보여줄 파일명. */
public record StoredFile(Path path, String originalName) {
}
