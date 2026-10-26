package com.example.backend.ocr.service;

import com.example.backend.ocr.client.EngineInfo;
import com.example.backend.ocr.client.OcrClient;
import org.springframework.stereotype.Service;

/** 엔진/망 모드 조회 (4). 실제 FastAPI 연동 시 30초 캐시를 여기에 둔다. */
@Service
public class EngineService {

    private final OcrClient ocrClient;

    public EngineService(OcrClient ocrClient) {
        this.ocrClient = ocrClient;
    }

    public EngineInfo getEngines() {
        return ocrClient.getEngines();
    }
}
