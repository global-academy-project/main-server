package com.example.backend.stt.client;

import java.util.Optional;

/**
 * 음성 → 글 (STT). yaml app.stt.client: none(기본, 음성 입력 꺼짐) | mock(고정 문장, 화면 확인용) | fastapi(ai-model, Whisper).
 * 받아 적기만 한다. 글은 사람이 확인·고친 뒤 "칸 채우기"로 넘어간다 (측정값을 AI 가 넣지 않음).
 * 실패하면 비어 있는 값을 돌려주고 예외를 던지지 않는다.
 */
public interface SttClient {

    Optional<SttResult> transcribe(byte[] audio, String mimeType, String language);

    boolean enabled();
}
