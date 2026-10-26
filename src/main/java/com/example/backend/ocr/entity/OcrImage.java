package com.example.backend.ocr.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "ocr_images")
public class OcrImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "page_no", nullable = false)
    private Integer pageNo;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(nullable = false, length = 10)
    private String format;

    private Integer width;

    private Integer height;

    protected OcrImage() {
    }

    public OcrImage(Long runId, int pageNo, String filePath, String format, Integer width, Integer height) {
        this.runId = runId;
        this.pageNo = pageNo;
        this.filePath = filePath;
        this.format = format;
        this.width = width;
        this.height = height;
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public Integer getPageNo() { return pageNo; }
    public String getFilePath() { return filePath; }
    public String getFormat() { return format; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
}
