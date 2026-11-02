package com.example.backend.site.dto;

import com.example.backend.site.entity.Equipment;

import java.time.LocalDate;

/** 설비. inspectionOverdue: 다음 점검·교정일이 오늘보다 앞 */
public record EquipmentResponse(Long equipmentId, Long siteId, Long zoneId, String code, String name, String equipmentType,
                                LocalDate nextInspectionDate, boolean inspectionOverdue, boolean active) {

    public static EquipmentResponse from(Equipment e, LocalDate today) {
        return new EquipmentResponse(e.getId(), e.getSiteId(), e.getZoneId(), e.getCode(), e.getName(), e.getEquipmentType(),
                e.getNextInspectionDate(), e.isInspectionOverdue(today), e.isActive());
    }
}
