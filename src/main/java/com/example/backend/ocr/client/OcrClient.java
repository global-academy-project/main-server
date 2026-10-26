package com.example.backend.ocr.client;

import com.example.backend.common.type.Engine;

import java.nio.file.Path;

/** OCR 서버 호출 추상화. app.ocr.client 값으로 mock / real 구현을 바꿔 끼운다. */
public interface OcrClient {

    /** 엔진 목록과 망 모드 (F2). */
    EngineInfo getEngines();

    /** PDF 한 건을 처리 (F3). 실패하면 예외를 던진다. */
    OcrResult extract(Path pdf, Engine engine, boolean extractImages);
}
