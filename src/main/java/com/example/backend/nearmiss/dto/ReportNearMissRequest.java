package com.example.backend.nearmiss.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 아차사고 보고 (POST /api/v1/near-misses). 보고자는 로그인 사용자.
 *  - occurredAt: 오프셋 없는 현장 현지 시각 (Asia/Seoul). 지금보다 5분 넘게 뒤면 400
 *  - zoneId·workId: 선택, 같은 현장 것만
 */
public record ReportNearMissRequest(@NotNull @Positive Long siteId, @Positive Long zoneId, @Positive Long workId,
                                    @NotBlank @Size(max = 200) String title, @NotNull LocalDateTime occurredAt,
                                    @NotBlank @Size(max = 20000) String description,
                                    @Size(max = 10000) String immediateAction) {
}
