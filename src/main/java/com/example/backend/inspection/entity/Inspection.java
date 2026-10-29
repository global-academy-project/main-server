package com.example.backend.inspection.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 장비·작업구역 점검표. 테이블 규칙은 V4 inspections.
 * 장비 점검은 equipment_id, 구역 점검은 zone_id 필수 (같은 현장, 복합 외래키). 작업에서 만든 점검표면 work_id.
 * DRAFT 에서만 내용·항목을 고칠 수 있고 (V9 trg_insp_lock), 제출할 때 종합 결과가 필수다.
 */
@Entity
@Table(name = "inspections")
public class Inspection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_type", nullable = false, length = 20, updatable = false)
    private InspectionTarget targetType;

    @Column(name = "equipment_id", updatable = false)
    private Long equipmentId;

    @Column(name = "zone_id")
    private Long zoneId;

    @Column(name = "work_id")
    private Long workId;

    @Column(name = "inspect_date", nullable = false)
    private LocalDate inspectDate;

    /** 점검 양식 코드 (예: GRINDER_DAILY, HOT_WORK_ZONE) */
    @Column(name = "template_code", nullable = false, length = 50)
    private String templateCode;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 10)
    private InspectionResult result;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private InspectionStatus status = InspectionStatus.DRAFT;

    @Column(name = "form_data", columnDefinition = "json")
    private String formData;

    @Column(name = "inspector_id", nullable = false, updatable = false)
    private Long inspectorId;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected Inspection() {
    }

    private Inspection(Long siteId, InspectionTarget targetType, Long equipmentId, Long zoneId, LocalDate inspectDate,
                       String templateCode, Long inspectorId) {
        if (siteId == null || inspectDate == null || inspectorId == null) {
            throw new IllegalArgumentException("현장·점검일·점검자가 필요합니다.");
        }
        if (templateCode == null || templateCode.isBlank() || templateCode.length() > 50) {
            throw new IllegalArgumentException("점검 양식 코드가 필요합니다 (50자 이내).");
        }
        this.siteId = siteId;
        this.targetType = targetType;
        this.equipmentId = equipmentId;
        this.zoneId = zoneId;
        this.inspectDate = inspectDate;
        this.templateCode = templateCode.trim();
        this.inspectorId = inspectorId;
    }

    /** 장비 점검 (장비 필수, 구역은 선택) */
    public static Inspection forEquipment(Long siteId, Long equipmentId, Long zoneId, LocalDate inspectDate,
                                          String templateCode, Long inspectorId) {
        if (equipmentId == null) {
            throw new IllegalArgumentException("장비 점검은 장비가 필요합니다.");
        }
        return new Inspection(siteId, InspectionTarget.EQUIPMENT, equipmentId, zoneId, inspectDate, templateCode, inspectorId);
    }

    /** 작업구역 점검 */
    public static Inspection forZone(Long siteId, Long zoneId, LocalDate inspectDate, String templateCode, Long inspectorId) {
        if (zoneId == null) {
            throw new IllegalArgumentException("구역 점검은 구역이 필요합니다.");
        }
        return new Inspection(siteId, InspectionTarget.ZONE, null, zoneId, inspectDate, templateCode, inspectorId);
    }

    private void requireDraft() {
        if (!status.isEditable()) {
            throw new IllegalStateException("제출된 점검표는 고칠 수 없습니다 (현재 " + status + ").");
        }
    }

    /** 작업에서 만든 점검표면 작업 연결 (같은 현장) */
    public void linkWork(Long workId) {
        requireDraft();
        this.workId = workId;
    }

    public void updateContent(LocalDate inspectDate, String formData) {
        requireDraft();
        if (inspectDate == null) {
            throw new IllegalArgumentException("점검일이 필요합니다.");
        }
        this.inspectDate = inspectDate;
        this.formData = formData;
    }

    /** 제출. 종합 결과 필수 (DB ck_insp_done). 보통 InspectionResult.of(항목 결과들) */
    public void submit(InspectionResult result) {
        requireDraft();
        if (result == null) {
            throw new IllegalArgumentException("모든 항목의 결과를 적어야 제출할 수 있습니다.");
        }
        this.result = result;
        this.status = InspectionStatus.PENDING;
    }

    /** 제출 취소 → 다시 수정 가능 */
    public void withdraw() {
        requireStatus(InspectionStatus.PENDING);
        this.status = InspectionStatus.DRAFT;
    }

    public void approve() {
        requireStatus(InspectionStatus.PENDING);
        this.status = InspectionStatus.APPROVED;
    }

    public void reject() {
        requireStatus(InspectionStatus.PENDING);
        this.status = InspectionStatus.REJECTED;
    }

    private void requireStatus(InspectionStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("상태가 " + expected + " 일 때만 할 수 있습니다 (현재 " + status + ").");
        }
    }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public InspectionTarget getTargetType() { return targetType; }
    public Long getEquipmentId() { return equipmentId; }
    public Long getZoneId() { return zoneId; }
    public Long getWorkId() { return workId; }
    public LocalDate getInspectDate() { return inspectDate; }
    public String getTemplateCode() { return templateCode; }
    public InspectionResult getResult() { return result; }
    public InspectionStatus getStatus() { return status; }
    public String getFormData() { return formData; }
    public Long getInspectorId() { return inspectorId; }
    public int getLockVersion() { return lockVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
