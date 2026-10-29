package com.example.backend.worker.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 작업자 자격 (교육 이수·면허·건강진단). 테이블 규칙은 V2 worker_qualifications.
 * 밀폐공간 특별교육, 용접 자격 등 → 작업 배치·허가 시 유효한 자격인지 확인에 쓴다.
 * 이력이라 수정 없이 새로 추가한다 (updated_at 없음).
 */
@Entity
@Table(name = "worker_qualifications")
public class WorkerQualification {

    /** 대문자·숫자·밑줄, 대문자로 시작 (DB ck_wq_code). 예: CONFINED_SPACE_SPECIAL */
    public static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "worker_id", nullable = false, updatable = false)
    private Long workerId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "qual_type", nullable = false, length = 20, updatable = false)
    private QualificationType qualType;

    @Column(name = "qual_code", nullable = false, length = 50, updatable = false)
    private String qualCode;

    @Column(name = "qual_name", nullable = false, length = 100, updatable = false)
    private String qualName;

    @Column(name = "issued_on", nullable = false, updatable = false)
    private LocalDate issuedOn;

    /** 유효기간 없는 자격은 null */
    @Column(name = "expires_on", updatable = false)
    private LocalDate expiresOn;

    @Column(length = 100, updatable = false)
    private String issuer;

    @Column(name = "cert_no", length = 50, updatable = false)
    private String certNo;

    /** 수료증 사진 (attachments.id) */
    @Column(name = "proof_attachment_id")
    private Long proofAttachmentId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected WorkerQualification() {
    }

    public WorkerQualification(Long workerId, QualificationType qualType, String qualCode, String qualName,
                               LocalDate issuedOn, LocalDate expiresOn, String issuer, String certNo, Long createdBy) {
        if (workerId == null || qualType == null || createdBy == null) {
            throw new IllegalArgumentException("작업자·자격 종류·등록자가 필요합니다.");
        }
        if (qualCode == null || !CODE.matcher(qualCode).matches()) {
            throw new IllegalArgumentException("자격 코드는 대문자·숫자·밑줄로 씁니다 (예: CONFINED_SPACE_SPECIAL).");
        }
        if (qualName == null || qualName.isBlank()) {
            throw new IllegalArgumentException("자격 이름이 필요합니다.");
        }
        if (issuedOn == null) {
            throw new IllegalArgumentException("취득일이 필요합니다.");
        }
        if (expiresOn != null && expiresOn.isBefore(issuedOn)) {
            throw new IllegalArgumentException("만료일은 취득일 이후여야 합니다.");
        }
        this.workerId = workerId;
        this.qualType = qualType;
        this.qualCode = qualCode;
        this.qualName = qualName.trim();
        this.issuedOn = issuedOn;
        this.expiresOn = expiresOn;
        this.issuer = issuer;
        this.certNo = certNo;
        this.createdBy = createdBy;
    }

    /** 그 날짜에 유효한가 (취득일 이후, 만료일 포함 이전) */
    public boolean isValidOn(LocalDate date) {
        return !date.isBefore(issuedOn) && (expiresOn == null || !date.isAfter(expiresOn));
    }

    /** 수료증 사진 연결 (사진 업로드 후) */
    public void attachProof(Long attachmentId) { this.proofAttachmentId = attachmentId; }

    public Long getId() { return id; }
    public Long getWorkerId() { return workerId; }
    public QualificationType getQualType() { return qualType; }
    public String getQualCode() { return qualCode; }
    public String getQualName() { return qualName; }
    public LocalDate getIssuedOn() { return issuedOn; }
    public LocalDate getExpiresOn() { return expiresOn; }
    public String getIssuer() { return issuer; }
    public String getCertNo() { return certNo; }
    public Long getProofAttachmentId() { return proofAttachmentId; }
    public Long getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
