package com.example.backend.ocr.client;

import com.example.backend.common.type.Engine;
import com.example.backend.common.type.NetworkMode;

import java.util.List;

/** GET /api/engines (4) 응답이자 FastAPI GET /ocr/engines (F2) 응답 형식. */
public record EngineInfo(NetworkMode networkMode, Engine defaultEngine, List<EngineItem> engines) {
}
