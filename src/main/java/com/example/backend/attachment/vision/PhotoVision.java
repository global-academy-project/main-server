package com.example.backend.attachment.vision;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 사진 인식 결과 (ai-model POST /api/v1/vision/tbm-photo 의 data).
 *  - model: 인식 모델 이름 (예: YOLOX-Nano), photo_checks.model 에 남는다 (50자)
 *  - people: 사진 속 인원, helmetMissing: 안전모 미착용 인원 (판단 못 하면 null — ai-model 에 안전모 모델이 없으면 null)
 *  - boxes: 찾은 것 (label: person | helmet | no_helmet, score 0~1, x·y·w·h 는 사진 너비·높이에 대한 0~1 비율,
 *    EXIF 회전을 적용한 뒤 기준 = 브라우저가 보여 주는 방향)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PhotoVision(String model, Integer people, Integer helmetMissing, List<Box> boxes) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Box(String label, Double score, Double x, Double y, Double w, Double h) {
    }
}
