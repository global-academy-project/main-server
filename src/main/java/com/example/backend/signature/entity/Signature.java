package com.example.backend.signature.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 서명. 서명 순간의 문서 내용 해시를 같이 저장해, 서명 후 내용이 바뀌면 해시가 안 맞아 드러난다.
 * 테이블 규칙은 V6 signatures. 수정·삭제는 V9 trg_sig_* 가 막는다 (모든 컬럼 updatable = false).
 * <ul>
 *   <li>서명자: 계정(USER) 또는 계정 없는 작업자(WORKER). 작업자는 그 현장에 배정돼 있어야 함 (V6 fk_sig_worker)</li>
 *   <li>같은 사람이 같은 문서에 같은 자격으로 두 번 서명 금지 (V6 uk_sig_once, signer_key 는 생성 컬럼이라 매핑하지 않음)</li>
 * </ul>
 */
@Entity
@Table(name = "signatures")
public class Signature {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "signer_type", nullable = false, length = 10, updatable = false)
    private SignerType signerType;

    @Column(name = "user_id", updatable = false)
    private Long userId;

    @Column(name = "worker_id", updatable = false)
    private Long workerId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_type", nullable = false, length = 20, updatable = false)
    private SignatureTarget targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20, updatable = false)
    private SignaturePurpose purpose;

    /** 패드 서명 이미지 (attachments.id) */
    @Column(name = "image_attachment_id", updatable = false)
    private Long imageAttachmentId;

    /** 서명 시점 문서 내용 해시 (소문자 hex 64자) */
    @Column(name = "content_sha256", nullable = false, length = 64, updatable = false, columnDefinition = "char(64)")
    private String contentSha256;

    @Column(name = "signed_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime signedAt;

    @Column(length = 45, updatable = false)
    private String ip;

    protected Signature() {
    }

    private Signature(Long siteId, SignerType signerType, Long userId, Long workerId, SignatureTarget targetType,
                      Long targetId, SignaturePurpose purpose, String contentSha256, Long imageAttachmentId, String ip) {
        if (siteId == null || targetType == null || targetId == null || purpose == null) {
            throw new IllegalArgumentException("현장·대상 문서·서명 자격이 필요합니다.");
        }
        if (contentSha256 == null || !SHA256.matcher(contentSha256).matches()) {
            throw new IllegalArgumentException("문서 내용 해시는 소문자 SHA-256(64자)이어야 합니다.");
        }
        if (ip != null && ip.length() > 45) {
            throw new IllegalArgumentException("IP 주소 형식이 올바르지 않습니다.");
        }
        this.siteId = siteId;
        this.signerType = signerType;
        this.userId = userId;
        this.workerId = workerId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.purpose = purpose;
        this.contentSha256 = contentSha256;
        this.imageAttachmentId = imageAttachmentId;
        this.ip = ip;
    }

    /** 계정 있는 사람의 서명 (작성자·승인자 등) */
    public static Signature byUser(Long siteId, Long userId, SignatureTarget target, Long targetId,
                                   SignaturePurpose purpose, String contentSha256, Long imageAttachmentId, String ip) {
        if (userId == null) {
            throw new IllegalArgumentException("서명한 사용자가 필요합니다.");
        }
        return new Signature(siteId, SignerType.USER, userId, null, target, targetId, purpose,
                contentSha256, imageAttachmentId, ip);
    }

    /** 계정 없는 작업자의 서명 (TBM 참석, 화재감시자 등). 그 현장에 배정된 작업자여야 한다 */
    public static Signature byWorker(Long siteId, Long workerId, SignatureTarget target, Long targetId,
                                     SignaturePurpose purpose, String contentSha256, Long imageAttachmentId, String ip) {
        if (workerId == null) {
            throw new IllegalArgumentException("서명한 작업자가 필요합니다.");
        }
        return new Signature(siteId, SignerType.WORKER, null, workerId, target, targetId, purpose,
                contentSha256, imageAttachmentId, ip);
    }

    /** 지금 문서 내용 해시와 비교. 다르면 서명 후 내용이 바뀐 것 */
    public boolean matches(String currentSha256) {
        return contentSha256.equals(currentSha256);
    }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public SignerType getSignerType() { return signerType; }
    public Long getUserId() { return userId; }
    public Long getWorkerId() { return workerId; }
    public SignatureTarget getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public SignaturePurpose getPurpose() { return purpose; }
    public Long getImageAttachmentId() { return imageAttachmentId; }
    public String getContentSha256() { return contentSha256; }
    public LocalDateTime getSignedAt() { return signedAt; }
    public String getIp() { return ip; }
}
