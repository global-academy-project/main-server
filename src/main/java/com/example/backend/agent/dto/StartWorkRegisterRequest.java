package com.example.backend.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/agent/work-register: 작업 등록 대화 메시지 보내기.
 * conversationId 가 없으면 새 대화. permitId 는 이미 저장된 허가서를 다룰 때만 (판정이 그 허가서에 저장된다).
 */
public record StartWorkRegisterRequest(
        @NotNull @Positive Long siteId,
        @Positive Long conversationId,
        @Positive Long permitId,
        @NotBlank @Size(max = 2000) String message
) {
}
