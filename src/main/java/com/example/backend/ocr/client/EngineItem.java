package com.example.backend.ocr.client;

import com.example.backend.common.type.Engine;

/** 엔진 하나의 상태. available=false 이면 reason 에 사유. external=true 는 외부 전송 엔진. */
public record EngineItem(Engine name, boolean available, boolean external, String reason) {
}
