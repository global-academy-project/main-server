package com.example.backend.site.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 작업구역 (현장 안의 구역). 테이블 규칙은 V2 work_zones.
 * 같은 구역 아차사고 경고(시나리오 C), 구역별 점검, 작업·허가의 장소 기준이 된다. 지우지 않고 사용 중지.
 */
@Entity
@Table(name = "work_zones")
public class WorkZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    /** 현장 안에서 겹치지 않는 구역 코드 (예: R-201) */
    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    /** 예: PROCESS, TANK, PIPE_RACK, WAREHOUSE */
    @Column(name = "zone_type", length = 30)
    private String zoneType;

    /** 밀폐공간이면 TRUE → 밀폐공간 작업 판별에 쓴다 */
    @Column(name = "is_confined", nullable = false)
    private boolean confined;

    @Column(name = "hazard_note", length = 500)
    private String hazardNote;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected WorkZone() {
    }

    public WorkZone(Long siteId, String code, String name, String zoneType, boolean confined, String hazardNote) {
        this.siteId = siteId;
        update(code, name, zoneType, confined, hazardNote);
    }

    public void update(String code, String name, String zoneType, boolean confined, String hazardNote) {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("구역 코드와 이름이 필요합니다.");
        }
        this.code = code.trim();
        this.name = name.trim();
        this.zoneType = zoneType;
        this.confined = confined;
        this.hazardNote = hazardNote;
    }

    public void deactivate() { this.active = false; }

    public void activate() { this.active = true; }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getZoneType() { return zoneType; }
    public boolean isConfined() { return confined; }
    public String getHazardNote() { return hazardNote; }
    public boolean isActive() { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
