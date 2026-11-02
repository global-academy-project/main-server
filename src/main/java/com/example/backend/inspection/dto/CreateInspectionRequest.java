package com.example.backend.inspection.dto;

import com.example.backend.inspection.entity.InspectionTarget;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 점검표 만들기 (POST /api/v1/sites/{siteId}/inspections). 양식의 항목을 복사해 작성 중(DRAFT)으로 만든다.
 *  - targetType ZONE: zoneId 필수 / EQUIPMENT: equipmentId 필수 (zoneId 를 비우면 설비가 놓인 구역)
 *  - templateCode 의 대상 종류와 targetType 이 같아야 한다
 *  - workId: 작업에서 만든 점검표면 (같은 현장)
 */
public record CreateInspectionRequest(@NotNull InspectionTarget targetType, Long zoneId, Long equipmentId, Long workId,
                                      @NotNull LocalDate inspectDate, @NotBlank @Size(max = 50) String templateCode) {
}
