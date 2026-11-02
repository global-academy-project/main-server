package com.example.backend.work.dto;

import jakarta.validation.constraints.Size;

/** 경고 확인. 긴급(CRITICAL) 경고는 note(조치 내용) 필수 */
public record AcknowledgeWarningRequest(@Size(max = 300) String note) {
}
