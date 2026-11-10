package com.example.backend.stt.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 화면 확인용 가짜 받아 적기: 소리와 상관없이 고정 문장. 모델 없이 녹음 → 입력칸 흐름을 볼 때 쓴다 */
@Component
@ConditionalOnProperty(name = "app.stt.client", havingValue = "mock")
public class MockSttClient implements SttClient {

    @Override
    public Optional<SttResult> transcribe(byte[] audio, String mimeType, String language) {
        return Optional.of(new SttResult("산소 20.9 황화수소 3 감시인 김도윤", language, "mock"));
    }

    @Override
    public boolean enabled() {
        return true;
    }
}
