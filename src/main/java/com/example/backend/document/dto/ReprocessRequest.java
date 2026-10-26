package com.example.backend.document.dto;

import com.example.backend.common.type.Engine;

/** 재처리 요청 본문 (12). engine 필수, extractImages 생략 시 false. */
public record ReprocessRequest(Engine engine, Boolean extractImages) {
}
