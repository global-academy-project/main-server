package com.example.backend.stt.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 음성 입력 꺼짐 (기본). 화면은 마이크 버튼을 숨긴다 */
@Component
@ConditionalOnProperty(name = "app.stt.client", havingValue = "none", matchIfMissing = true)
public class NoSttClient implements SttClient {

    @Override
    public Optional<SttResult> transcribe(byte[] audio, String mimeType, String language) {
        return Optional.empty();
    }

    @Override
    public boolean enabled() {
        return false;
    }
}
