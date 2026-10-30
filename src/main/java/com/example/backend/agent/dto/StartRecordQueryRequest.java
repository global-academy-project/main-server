package com.example.backend.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 기록 질문 (POST /api/v1/agent/record-query). 작업 등록 요청과 같은 모양에서 permitId 만 없다.
 * conversationId 는 이어 묻기일 때만. 기록 질문 대화에만 이어 보낼 수 있다 (작업 등록 대화와 섞지 않음).
 */
public record StartRecordQueryRequest(
        @NotNull @Positive Long siteId,
        @Positive Long conversationId,
        @NotBlank @Size(max = 2000) String message
) {
}
