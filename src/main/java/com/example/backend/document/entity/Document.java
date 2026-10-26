package com.example.backend.document.entity;

import com.example.backend.common.type.DocumentStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "documents")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "stored_path", nullable = false, length = 500)
    private String storedPath;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "page_count")
    private Integer pageCount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private DocumentStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected Document() {
    }

    public Document(Long userId, String originalName, String storedPath, long fileSize) {
        this.userId = userId;
        this.originalName = originalName;
        this.storedPath = storedPath;
        this.fileSize = fileSize;
        this.status = DocumentStatus.UPLOADED;
    }

    public void changeStatus(DocumentStatus status) { this.status = status; }
    public void updatePageCount(Integer pageCount) { this.pageCount = pageCount; }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getOriginalName() { return originalName; }
    public String getStoredPath() { return storedPath; }
    public Long getFileSize() { return fileSize; }
    public Integer getPageCount() { return pageCount; }
    public DocumentStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
