package com.example.backend.site.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 설비·장비 (크레인, 리프트, 용접기, 가스 측정기 등). 테이블 규칙은 V2 equipment.
 * 측정기는 next_inspection_date 가 다음 교정일 → 교정일 지난 측정기로 잰 값 판정에 쓸 수 있다.
 */
@Entity
@Table(name = "equipment")
public class Equipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    /** 놓인 구역 (같은 현장 구역만, DB 외래키 (zone_id, site_id)) */
    @Column(name = "zone_id")
    private Long zoneId;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    /** 예: CRANE, LIFT, WELDER, GAS_DETECTOR */
    @Column(name = "equipment_type", nullable = false, length = 30)
    private String equipmentType;

    @Column(name = "next_inspection_date")
    private LocalDate nextInspectionDate;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected Equipment() {
    }

    public Equipment(Long siteId, Long zoneId, String code, String name, String equipmentType, LocalDate nextInspectionDate) {
        this.siteId = siteId;
        update(zoneId, code, name, equipmentType, nextInspectionDate);
    }

    public void update(Long zoneId, String code, String name, String equipmentType, LocalDate nextInspectionDate) {
        if (code == null || code.isBlank() || name == null || name.isBlank() || equipmentType == null || equipmentType.isBlank()) {
            throw new IllegalArgumentException("설비 코드·이름·종류가 필요합니다.");
        }
        this.zoneId = zoneId;
        this.code = code.trim();
        this.name = name.trim();
        this.equipmentType = equipmentType.trim();
        this.nextInspectionDate = nextInspectionDate;
    }

    /** 다음 점검(교정)일이 지났는지. 날짜가 없으면 false */
    public boolean isInspectionOverdue(LocalDate today) {
        return nextInspectionDate != null && nextInspectionDate.isBefore(today);
    }

    public void deactivate() { this.active = false; }

    public void activate() { this.active = true; }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public Long getZoneId() { return zoneId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getEquipmentType() { return equipmentType; }
    public LocalDate getNextInspectionDate() { return nextInspectionDate; }
    public boolean isActive() { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
