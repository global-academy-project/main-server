package com.example.backend.site.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 현장·구역에서 취급하는 화학물질. 테이블 규칙은 V2 site_chemicals.
 * 작업 장소 주변 물질 → MSDS 조회(lookup_msds)·위험성평가 근거. zone_id 가 없으면 현장 전체(창고 등).
 */
@Entity
@Table(name = "site_chemicals")
public class SiteChemical {

    /** CAS 번호 형식 (DB ck_sc_cas) */
    public static final Pattern CAS_NO = Pattern.compile("^[0-9]{2,7}-[0-9]{2}-[0-9]$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Column(name = "zone_id")
    private Long zoneId;

    /** DB 생성 컬럼 COALESCE(zone_id, 0) (같은 구역·이름 중복 방지용). 읽기만 */
    @Column(name = "zone_key", insertable = false, updatable = false)
    private Long zoneKey;

    /** 혼합물·영업비밀 등은 없을 수 있음 */
    @Column(name = "cas_no", length = 20)
    private String casNo;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "max_quantity", precision = 14, scale = 3)
    private BigDecimal maxQuantity;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 10)
    private ChemicalUnit unit;

    /** 비치된 MSDS 개정일 */
    @Column(name = "msds_revised_on")
    private LocalDate msdsRevisedOn;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected SiteChemical() {
    }

    public SiteChemical(Long siteId, Long zoneId, String casNo, String name, BigDecimal maxQuantity, ChemicalUnit unit,
                        LocalDate msdsRevisedOn) {
        this.siteId = siteId;
        update(zoneId, casNo, name, maxQuantity, unit, msdsRevisedOn);
    }

    public void update(Long zoneId, String casNo, String name, BigDecimal maxQuantity, ChemicalUnit unit,
                       LocalDate msdsRevisedOn) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("물질 이름이 필요합니다.");
        }
        if (casNo != null && !CAS_NO.matcher(casNo).matches()) {
            throw new IllegalArgumentException("CAS 번호 형식이 아닙니다 (예: 108-88-3).");
        }
        if ((maxQuantity == null) != (unit == null)) {
            throw new IllegalArgumentException("최대 보관량과 단위는 함께 있어야 합니다.");
        }
        if (maxQuantity != null && maxQuantity.signum() < 0) {
            throw new IllegalArgumentException("최대 보관량은 0 이상이어야 합니다.");
        }
        this.zoneId = zoneId;
        this.casNo = casNo;
        this.name = name.trim();
        this.maxQuantity = maxQuantity;
        this.unit = unit;
        this.msdsRevisedOn = msdsRevisedOn;
    }

    public void deactivate() { this.active = false; }

    public void activate() { this.active = true; }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public Long getZoneId() { return zoneId; }
    public Long getZoneKey() { return zoneKey; }
    public String getCasNo() { return casNo; }
    public String getName() { return name; }
    public BigDecimal getMaxQuantity() { return maxQuantity; }
    public ChemicalUnit getUnit() { return unit; }
    public LocalDate getMsdsRevisedOn() { return msdsRevisedOn; }
    public boolean isActive() { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
