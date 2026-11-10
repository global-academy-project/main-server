package com.example.backend.stt.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 받아 적은 결과 (ai-model POST /api/v1/stt/transcribe 의 data). model: 예) whisper-small */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SttResult(String text, String language, String model) {
}
