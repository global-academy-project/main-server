package com.example.backend.attachment.vision;

import java.util.Optional;

/**
 * TBM 사진 인식 (사진 속 인원·안전모). yaml app.photo.vision.client: none(기본, 인식 안 함) | mock(고정 값, 화면 확인용) | fastapi(ai-model).
 * 실패하면 비어 있는 값을 돌려주고 예외를 던지지 않는다 (파일 정보 확인 결과만으로 계속, 작성자가 결과 고치기로 적을 수 있음).
 */
public interface PhotoVisionClient {

    Optional<PhotoVision> detect(byte[] image, String mimeType, String fileName);

    /** 인식을 하는가 (none 이면 false) */
    boolean enabled();
}
