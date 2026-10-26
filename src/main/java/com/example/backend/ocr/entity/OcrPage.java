package com.example.backend.ocr.entity;

import com.example.backend.common.type.PageSource;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

@Entity
@Table(name = "ocr_pages")
public class OcrPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "page_no", nullable = false)
    private Integer pageNo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private PageSource source;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(columnDefinition = "longtext")
    private String text;

    @Column(precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "processing_ms")
    private Integer processingMs;

    protected OcrPage() {
    }

    public OcrPage(Long runId, int pageNo, PageSource source, String text,
                   BigDecimal confidence, Integer processingMs) {
        this.runId = runId;
        this.pageNo = pageNo;
        this.source = source;
        this.text = text;
        this.confidence = confidence;
        this.processingMs = processingMs;
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public Integer getPageNo() { return pageNo; }
    public PageSource getSource() { return source; }
    public String getText() { return text; }
    public BigDecimal getConfidence() { return confidence; }
    public Integer getProcessingMs() { return processingMs; }
}
