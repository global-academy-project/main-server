package com.example.backend.attachment.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 사진 확인 결과 한 번 (AI 인식 + 규칙 비교). 테이블 규칙은 V4 photo_checks.
 * 다시 확인하면 행을 새로 추가한다 (이력). 인식 값(인원·날짜·구역 일치)은 모르면 null.
 * duplicate_of: 같은 해시의 이전 첨부 (사진 재사용 의심). agent_run_id: 인식한 에이전트 실행.
 */
@Entity
@Table(name = "photo_checks")
public class PhotoCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attachment_id", nullable = false, updatable = false)
    private Long attachmentId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "check_type", nullable = false, length = 10, updatable = false)
    private PhotoCheckType checkType;

    @Column(name = "detected_people", updatable = false)
    private Integer detectedPeople;

    @Column(name = "detected_date", updatable = false)
    private LocalDate detectedDate;

    @Column(name = "zone_match", updatable = false)
    private Boolean zoneMatch;

    @Column(name = "duplicate_of", updatable = false)
    private Long duplicateOf;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10, updatable = false)
    private PhotoCheckResult result;

    /** 인식 원본·사유 (JSON 문자열) */
    @Column(updatable = false, columnDefinition = "json")
    private String detail;

    @Column(length = 50, updatable = false)
    private String model;

    @Column(name = "agent_run_id", updatable = false)
    private Long agentRunId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected PhotoCheck() {
    }

    public PhotoCheck(Long attachmentId, PhotoCheckType checkType, Integer detectedPeople, LocalDate detectedDate,
                      Boolean zoneMatch, Long duplicateOf, PhotoCheckResult result, String detail, String model,
                      Long agentRunId) {
        if (attachmentId == null || checkType == null || result == null) {
            throw new IllegalArgumentException("첨부·확인 종류·결과가 필요합니다.");
        }
        if (detectedPeople != null && detectedPeople < 0) {
            throw new IllegalArgumentException("인원은 0 이상입니다.");
        }
        if (duplicateOf != null && duplicateOf.equals(attachmentId)) {
            throw new IllegalArgumentException("자기 자신을 중복 대상으로 둘 수 없습니다.");
        }
        if (model != null && model.length() > 50) {
            throw new IllegalArgumentException("모델 이름은 50자 이내입니다.");
        }
        this.attachmentId = attachmentId;
        this.checkType = checkType;
        this.detectedPeople = detectedPeople;
        this.detectedDate = detectedDate;
        this.zoneMatch = zoneMatch;
        this.duplicateOf = duplicateOf;
        this.result = result;
        this.detail = detail;
        this.model = model;
        this.agentRunId = agentRunId;
    }

    public Long getId() { return id; }
    public Long getAttachmentId() { return attachmentId; }
    public PhotoCheckType getCheckType() { return checkType; }
    public Integer getDetectedPeople() { return detectedPeople; }
    public LocalDate getDetectedDate() { return detectedDate; }
    public Boolean getZoneMatch() { return zoneMatch; }
    public Long getDuplicateOf() { return duplicateOf; }
    public PhotoCheckResult getResult() { return result; }
    public String getDetail() { return detail; }
    public String getModel() { return model; }
    public Long getAgentRunId() { return agentRunId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
