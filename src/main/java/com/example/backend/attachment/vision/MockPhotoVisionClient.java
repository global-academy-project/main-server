package com.example.backend.attachment.vision;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** 화면 확인용 가짜 인식: 사람 3명, 안전모는 모름. 모델 없이 흐름(인식 → 확인 → 고치기)을 볼 때 쓴다 */
@Component
@ConditionalOnProperty(name = "app.photo.vision.client", havingValue = "mock")
public class MockPhotoVisionClient implements PhotoVisionClient {

    @Override
    public Optional<PhotoVision> detect(byte[] image, String mimeType, String fileName) {
        return Optional.of(new PhotoVision("mock", 3, null, List.of(
                new PhotoVision.Box("person", 0.91, 0.10, 0.25, 0.18, 0.60),
                new PhotoVision.Box("person", 0.88, 0.41, 0.22, 0.18, 0.62),
                new PhotoVision.Box("person", 0.76, 0.72, 0.27, 0.18, 0.58))));
    }

    @Override
    public boolean enabled() {
        return true;
    }
}
