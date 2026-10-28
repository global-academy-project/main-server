package com.example.backend.permit.dto;

import com.example.backend.permit.entity.PermitType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * POST /api/v1/permits
 * form: 양식 입력값. 화기 예: {"extinguisher_ready": true, "spark_prevention": true, "flammable_vapor_removed": true}
 * watcherName 은 규칙 입력 form.watcher_name 으로 들어간다.
 */
public record CreatePermitRequest(
        @NotNull Long workId,
        @NotNull PermitType permitType,
        @NotNull LocalDateTime validFrom,
        @NotNull LocalDateTime validTo,
        @Size(max = 50) String watcherName,
        @Size(max = 50) String supervisorName,
        Map<String, Object> form
) {
}
