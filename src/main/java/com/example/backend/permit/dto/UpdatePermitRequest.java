package com.example.backend.permit.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Map;

/** PUT /api/v1/permits/{id}: 내용 전체 교체 (작성 중·차단 상태에서만). lockVersion 은 조회 값 그대로 */
public record UpdatePermitRequest(
        @NotNull LocalDateTime validFrom,
        @NotNull LocalDateTime validTo,
        @Size(max = 50) String watcherName,
        @Size(max = 50) String supervisorName,
        Map<String, Object> form,
        @NotNull Integer lockVersion
) {
}
