package com.example.backend.attachment.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 첨부 파일 (사진·도면·PDF) 공용. 테이블 규칙은 V4 attachments.
 * 파일은 저장소(stored_path, 전역에서 하나)에 두고 이 행에는 경로·해시만. sha256 은 같은 사진 재사용 탐지용
 * (예: 어제 TBM 사진을 오늘 다시 올림 → photo_checks.duplicate_of).
 * 지우지 않는다 (사진 확인·자격 증빙이 참조). 소유 기록은 임시저장(DRAFT)에서 실제 기록으로만 옮길 수 있다.
 */
@Entity
@Table(name = "attachments")
public class Attachment {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "owner_type", nullable = false, length = 20)
    private AttachmentOwner ownerType;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10, updatable = false)
    private AttachmentKind kind = AttachmentKind.PHOTO;

    @Column(name = "original_name", nullable = false, length = 255, updatable = false)
    private String originalName;

    /** 저장소 안 경로 (전역에서 하나, uk_att_path) */
    @Column(name = "stored_path", nullable = false, length = 500, updatable = false)
    private String storedPath;

    @Column(name = "mime_type", length = 100, updatable = false)
    private String mimeType;

    @Column(name = "file_size", updatable = false)
    private Long fileSize;

    /** 소문자 16진수 64자 */
    @Column(length = 64, updatable = false, columnDefinition = "char(64)")
    private String sha256;

    /** 사진 촬영 시각 (EXIF 등, 없으면 null) */
    @Column(name = "taken_at", updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime takenAt;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private Long uploadedBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected Attachment() {
    }

    public Attachment(Long siteId, AttachmentOwner ownerType, Long ownerId, AttachmentKind kind, String originalName,
                      String storedPath, String mimeType, Long fileSize, String sha256, LocalDateTime takenAt,
                      Long uploadedBy) {
        if (siteId == null || ownerType == null || ownerId == null || uploadedBy == null) {
            throw new IllegalArgumentException("현장·소유 기록·올린 사람이 필요합니다.");
        }
        if (originalName == null || originalName.isBlank() || originalName.length() > 255) {
            throw new IllegalArgumentException("원래 파일 이름이 필요합니다 (255자 이내).");
        }
        if (storedPath == null || storedPath.isBlank() || storedPath.length() > 500) {
            throw new IllegalArgumentException("저장 경로가 필요합니다 (500자 이내).");
        }
        if (fileSize != null && fileSize < 0) {
            throw new IllegalArgumentException("파일 크기는 0 이상입니다.");
        }
        if (sha256 != null && !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 은 소문자 16진수 64자입니다.");
        }
        this.siteId = siteId;
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.kind = kind == null ? AttachmentKind.PHOTO : kind;
        this.originalName = originalName;
        this.storedPath = storedPath;
        this.mimeType = mimeType;
        this.fileSize = fileSize;
        this.sha256 = sha256;
        this.takenAt = takenAt;
        this.uploadedBy = uploadedBy;
    }

    /** 임시저장(DRAFT)에서 올린 첨부를 실제 기록으로 옮긴다. 실제 기록끼리는 옮기지 않는다 */
    public void moveTo(AttachmentOwner ownerType, Long ownerId) {
        if (this.ownerType != AttachmentOwner.DRAFT) {
            throw new IllegalStateException("임시저장 첨부만 다른 기록으로 옮길 수 있습니다.");
        }
        if (ownerType == null || ownerType == AttachmentOwner.DRAFT || ownerId == null) {
            throw new IllegalArgumentException("옮길 실제 기록이 필요합니다.");
        }
        this.ownerType = ownerType;
        this.ownerId = ownerId;
    }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public AttachmentOwner getOwnerType() { return ownerType; }
    public Long getOwnerId() { return ownerId; }
    public AttachmentKind getKind() { return kind; }
    public String getOriginalName() { return originalName; }
    public String getStoredPath() { return storedPath; }
    public String getMimeType() { return mimeType; }
    public Long getFileSize() { return fileSize; }
    public String getSha256() { return sha256; }
    public LocalDateTime getTakenAt() { return takenAt; }
    public Long getUploadedBy() { return uploadedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
