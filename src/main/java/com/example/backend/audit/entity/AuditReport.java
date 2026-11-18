package com.example.backend.audit.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 감사보고서 (V11 audit_reports). 집계 원본(dataSnapshot)은 만들 때 한 번 쓰고 고치지 않는다.
 * 사람이 고치는 것은 제목·종합 의견·항목별 요약(itemsJson 안의 summary)뿐. 삭제는 숨김.
 */
@Entity
@Table(name = "audit_reports")
public class AuditReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "date_from", nullable = false, updatable = false)
    private LocalDate dateFrom;

    @Column(name = "date_to", nullable = false, updatable = false)
    private LocalDate dateTo;

    @Column(name = "query_types", nullable = false, length = 300, updatable = false)
    private String queryTypes;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private AuditReportStatus status;

    @Column(name = "overall_verification", length = 10)
    private String overallVerification;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "items_json", nullable = false, columnDefinition = "longtext")
    private String itemsJson;

    @Column(name = "data_snapshot", nullable = false, updatable = false, columnDefinition = "longtext")
    private String dataSnapshot;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "deleted_at", columnDefinition = "datetime(6)")
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected AuditReport() {
    }

    public AuditReport(Long siteId, String title, LocalDate dateFrom, LocalDate dateTo, String queryTypes,
                       AuditReportStatus status, String overallVerification, String failureReason,
                       String itemsJson, String dataSnapshot, Long createdBy) {
        if (siteId == null || dateFrom == null || dateTo == null || createdBy == null) {
            throw new IllegalArgumentException("현장·기간·작성자가 필요합니다.");
        }
        if (dateFrom.isAfter(dateTo)) {
            throw new IllegalArgumentException("조회 시작일은 종료일보다 늦을 수 없습니다.");
        }
        this.siteId = siteId;
        this.dateFrom = dateFrom;
        this.dateTo = dateTo;
        this.queryTypes = queryTypes;
        this.status = status;
        this.overallVerification = overallVerification;
        this.failureReason = failureReason;
        this.dataSnapshot = dataSnapshot;
        this.createdBy = createdBy;
        edit(title, null, itemsJson);
    }

    /** 제목·종합 의견·항목(요약) 고치기 (삭제된 보고서는 불가) */
    public void edit(String title, String summary, String itemsJson) {
        if (deletedAt != null) {
            throw new IllegalStateException("삭제된 감사보고서입니다.");
        }
        if (status == AuditReportStatus.GENERATING && this.itemsJson != null) {
            throw new IllegalStateException("AI 요약 중인 감사보고서는 고칠 수 없습니다. 요약이 끝난 뒤 고쳐 주세요.");
        }
        if (title == null || title.isBlank() || itemsJson == null) {
            throw new IllegalArgumentException("제목이 필요합니다.");
        }
        this.title = title.trim();
        this.summary = summary;
        this.itemsJson = itemsJson;
    }

    /** AI 요약 다시 하기 (요약 실패, 또는 요약 중 상태로 오래 멈춘 경우) */
    public void restartSummary() {
        if (deletedAt != null) {
            throw new IllegalStateException("삭제된 감사보고서입니다.");
        }
        this.status = AuditReportStatus.GENERATING;
        this.failureReason = null;
    }

    /** AI 요약·검증 결과 반영 */
    public void applySummary(AuditReportStatus status, String overallVerification, String failureReason, String itemsJson) {
        this.status = status;
        this.overallVerification = overallVerification;
        this.failureReason = failureReason == null || failureReason.length() <= 500 ? failureReason : failureReason.substring(0, 500);
        this.itemsJson = itemsJson;
    }

    public void delete(Long userId, LocalDateTime now) {
        if (deletedAt != null) {
            throw new IllegalStateException("이미 삭제된 감사보고서입니다.");
        }
        this.deletedAt = now;
        this.deletedBy = userId;
    }

    public boolean isDeleted() { return deletedAt != null; }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public String getTitle() { return title; }
    public LocalDate getDateFrom() { return dateFrom; }
    public LocalDate getDateTo() { return dateTo; }
    public String getQueryTypes() { return queryTypes; }
    public AuditReportStatus getStatus() { return status; }
    public String getOverallVerification() { return overallVerification; }
    public String getFailureReason() { return failureReason; }
    public String getSummary() { return summary; }
    public String getItemsJson() { return itemsJson; }
    public String getDataSnapshot() { return dataSnapshot; }
    public Long getCreatedBy() { return createdBy; }
    public int getLockVersion() { return lockVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
