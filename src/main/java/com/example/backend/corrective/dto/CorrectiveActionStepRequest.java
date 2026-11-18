package com.example.backend.corrective.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 개선조치 상태 바꾸기: 시작·완료·확인·미흡(다시 조치)·취소. note 는 완료 때 조치 내용(필수) */
public record CorrectiveActionStepRequest(@NotNull Integer lockVersion, @Size(max = 4000) String note) {
}
