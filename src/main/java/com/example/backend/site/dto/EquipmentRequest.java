package com.example.backend.site.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 설비 등록·수정 (현장 관리자 이상).
 *  - equipmentType: 예 CRANE, LIFT, WELDER, GAS_DETECTOR (대문자·숫자·_)
 *  - zoneId: 놓인 구역 (같은 현장 구역만, 비우면 없음)
 *  - nextInspectionDate: 다음 점검·교정일 (지나면 대시보드·판정에서 표시)
 *  - active: 수정 때만. false 면 사용 중지
 */
public record EquipmentRequest(@NotBlank @Size(max = 30) String code, @NotBlank @Size(max = 100) String name,
                               @NotBlank @Size(max = 30) String equipmentType, Long zoneId,
                               LocalDate nextInspectionDate, Boolean active) {
}
