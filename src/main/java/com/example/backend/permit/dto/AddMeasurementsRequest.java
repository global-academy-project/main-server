package com.example.backend.permit.dto;

import com.example.backend.permit.entity.MeasurementItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * POST /api/v1/permits/{id}/measurements: 작업 전 측정값 추가 (고치지 않고 재측정 값을 추가).
 * 단위는 항목마다 고정 (O2·CO2 %, LEL %LEL, H2S·CO·NH3·CL2 ppm). measuredAt 을 비우면 지금 시각.
 */
public record AddMeasurementsRequest(@NotEmpty List<@Valid Item> items) {

    public record Item(
            @NotNull MeasurementItem item,
            @NotNull @DecimalMin("0") BigDecimal value,
            LocalDateTime measuredAt,
            @Size(max = 100) String location,
            @Size(max = 50) String measurerName
    ) {
    }
}
