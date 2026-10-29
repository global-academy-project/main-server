package com.example.backend.work.entity;

import com.example.backend.site.entity.ChemicalUnit;
import com.example.backend.site.entity.SiteChemical;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 작업에서 다루는 물질. 테이블 규칙은 V3 work_materials.
 * CAS 번호로 MSDS 조회(lookup_msds)·태깅("지난달 톨루엔 다룬 작업")에 쓴다.
 * 작업이 승인·진행·완료·취소 상태면 추가·수정·삭제 불가 (V9 trg_wm_lock_*) → 서비스에서 작업 상태를 먼저 확인.
 */
@Entity
@Table(name = "work_materials")
public class WorkMaterial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "work_id", nullable = false, updatable = false)
    private Long workId;

    @Column(name = "cas_no", length = 20)
    private String casNo;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(precision = 14, scale = 3)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 10)
    private ChemicalUnit unit;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected WorkMaterial() {
    }

    public WorkMaterial(Long workId, String casNo, String name, BigDecimal quantity, ChemicalUnit unit) {
        if (workId == null) {
            throw new IllegalArgumentException("작업이 필요합니다.");
        }
        this.workId = workId;
        update(casNo, name, quantity, unit);
    }

    /** 같은 규칙: CAS 형식, 양·단위는 함께 (DB ck_wm_cas, ck_wm_qty) */
    public void update(String casNo, String name, BigDecimal quantity, ChemicalUnit unit) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("물질 이름이 필요합니다.");
        }
        if (casNo != null && !SiteChemical.CAS_NO.matcher(casNo).matches()) {
            throw new IllegalArgumentException("CAS 번호 형식이 아닙니다 (예: 108-88-3).");
        }
        if ((quantity == null) != (unit == null)) {
            throw new IllegalArgumentException("양과 단위는 함께 있어야 합니다.");
        }
        if (quantity != null && quantity.signum() < 0) {
            throw new IllegalArgumentException("양은 0 이상이어야 합니다.");
        }
        this.casNo = casNo;
        this.name = name.trim();
        this.quantity = quantity;
        this.unit = unit;
    }

    public Long getId() { return id; }
    public Long getWorkId() { return workId; }
    public String getCasNo() { return casNo; }
    public String getName() { return name; }
    public BigDecimal getQuantity() { return quantity; }
    public ChemicalUnit getUnit() { return unit; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
