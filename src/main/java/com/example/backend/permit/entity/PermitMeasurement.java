package com.example.backend.permit.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 가스 측정값. 테이블 규칙은 V4 permit_measurements. 증거라 고치거나 지우지 않고 재측정 값을 추가한다 (V9).
 * phase: PRE_WORK(작업 전, 허가 신청 단계) / DURING(작업 중) / POST_WORK(작업 후). 단계별로 넣을 수 있는 허가 상태가 다르다 (V9).
 */
@Entity
@Table(name = "permit_measurements")
public class PermitMeasurement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "permit_id", nullable = false, updatable = false)
    private Long permitId;

    /** PRE_WORK / DURING / POST_WORK */
    @Column(nullable = false, updatable = false, length = 10)
    private String phase = "PRE_WORK";

    @Column(name = "measured_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime measuredAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private MeasurementItem item;

    @Column(nullable = false, updatable = false, precision = 10, scale = 3)
    private BigDecimal value;

    @Column(nullable = false, updatable = false, length = 10)
    private String unit;

    @Column(updatable = false, length = 100)
    private String location;

    @Column(name = "measurer_name", updatable = false, length = 50)
    private String measurerName;

    /** 측정기 이름 (설비로 등록되지 않은 측정기) */
    @Column(updatable = false, length = 100)
    private String instrument;

    /** 등록된 측정기 (equipment) → 교정일 지난 측정기 판정에 쓸 수 있음 */
    @Column(name = "instrument_equipment_id", updatable = false)
    private Long instrumentEquipmentId;

    @Column(name = "is_remeasure", nullable = false, updatable = false)
    private boolean remeasure;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected PermitMeasurement() {
    }

    public PermitMeasurement(Long permitId, LocalDateTime measuredAt, MeasurementItem item, BigDecimal value,
                             String location, String measurerName, boolean remeasure) {
        this.permitId = permitId;
        this.measuredAt = measuredAt;
        this.item = item;
        this.value = value;
        this.unit = item.unit();
        this.location = location;
        this.measurerName = measurerName;
        this.remeasure = remeasure;
    }

    public Long getId() { return id; }
    public Long getPermitId() { return permitId; }
    public String getPhase() { return phase; }
    public LocalDateTime getMeasuredAt() { return measuredAt; }
    public MeasurementItem getItem() { return item; }
    public BigDecimal getValue() { return value; }
    public String getUnit() { return unit; }
    public String getLocation() { return location; }
    public String getMeasurerName() { return measurerName; }
    public String getInstrument() { return instrument; }
    public Long getInstrumentEquipmentId() { return instrumentEquipmentId; }
    public boolean isRemeasure() { return remeasure; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
