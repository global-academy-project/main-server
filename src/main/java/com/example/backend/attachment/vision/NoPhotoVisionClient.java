package com.example.backend.attachment.vision;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 사진 인식 안 함 (기본). 파일 정보 확인(촬영 날짜·같은 사진)만 한다 */
@Component
@ConditionalOnProperty(name = "app.photo.vision.client", havingValue = "none", matchIfMissing = true)
public class NoPhotoVisionClient implements PhotoVisionClient {

    @Override
    public Optional<PhotoVision> detect(byte[] image, String mimeType, String fileName) {
        return Optional.empty();
    }

    @Override
    public boolean enabled() {
        return false;
    }
}
