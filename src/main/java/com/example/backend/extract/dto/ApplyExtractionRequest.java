package com.example.backend.extract.dto;

import com.example.backend.permit.entity.MeasurementItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 사용자가 확인한 추출값을 허가서에 넣기 (POST /api/v1/permits/{permitId}/extraction).
 *  - text: 추출에 쓴 원문 (스냅샷에 그대로 남는다)
 *  - measurements: 확인(필요하면 고친) 측정값. 작업 전 측정값으로 추가된다
 *  - form: 확인한 양식 값. 그 허가 종류 규칙의 form 항목만 받는다 (watcher_name 은 감시자 칸으로)
 *  - lockVersion: 허가서 최신 버전
 */
public record ApplyExtractionRequest(@NotNull Integer lockVersion, @NotBlank @Size(max = 2000) String text,
                                     @Size(max = 7) List<@Valid @NotNull Measurement> measurements,
                                     @Size(max = 20) Map<String, Object> form) {

    public record Measurement(@NotNull MeasurementItem item, @NotNull @DecimalMin("0") BigDecimal value) {
    }
}
