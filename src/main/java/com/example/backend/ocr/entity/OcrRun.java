package com.example.backend.ocr.entity;

import com.example.backend.common.type.Engine;
import com.example.backend.common.type.NetworkMode;
import com.example.backend.common.type.RunStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ocr_runs")
public class OcrRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Engine engine;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "network_mode", nullable = false, length = 10)
    private NetworkMode networkMode;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private RunStatus status;

    @Column(name = "extract_images", nullable = false)
    private boolean extractImages;

    @Column(name = "total_ms")
    private Integer totalMs;

    @Column(name = "avg_confidence", precision = 5, scale = 4)
    private BigDecimal avgConfidence;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(columnDefinition = "text")
    private String summary;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "finished_at", columnDefinition = "datetime(6)")
    private LocalDateTime finishedAt;

    protected OcrRun() {
    }

    public OcrRun(Long documentId, Engine engine, NetworkMode networkMode, boolean extractImages) {
        this.documentId = documentId;
        this.engine = engine;
        this.networkMode = networkMode;
        this.extractImages = extractImages;
        this.status = RunStatus.PROCESSING;
    }

    public void complete(Integer totalMs, BigDecimal avgConfidence) {
        this.status = RunStatus.DONE;
        this.totalMs = totalMs;
        this.avgConfidence = avgConfidence;
        this.finishedAt = LocalDateTime.now();
    }

    /**
     * OCR 서버 응답으로 완료 처리한다.
     * engine 은 요청 엔진(AUTO 등)에서 "실제 사용된 엔진"으로 갱신된다. (명세 §3)
     */
    public void applyResult(Engine actualEngine, NetworkMode networkMode,
                            Integer totalMs, BigDecimal avgConfidence, String summary) {
        this.engine = actualEngine;
        this.networkMode = networkMode;
        this.summary = summary;
        complete(totalMs, avgConfidence);
    }

    public void fail(String errorMessage) {
        this.status = RunStatus.FAILED;
        this.errorMessage = errorMessage;
        this.finishedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getDocumentId() { return documentId; }
    public Engine getEngine() { return engine; }
    public NetworkMode getNetworkMode() { return networkMode; }
    public RunStatus getStatus() { return status; }
    public boolean isExtractImages() { return extractImages; }
    public Integer getTotalMs() { return totalMs; }
    public BigDecimal getAvgConfidence() { return avgConfidence; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public String getSummary() { return summary; }
}