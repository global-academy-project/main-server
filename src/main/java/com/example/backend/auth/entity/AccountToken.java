package com.example.backend.auth.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 메일 링크 토큰 (이메일 인증·변경, 비밀번호 재설정). 테이블 규칙은 V2 account_tokens.
 * - 원문은 메일로만 보내고 여기엔 SHA-256(hex 64자)만 저장한다 (ck_at_hash)
 * - 사용자·용도당 살아 있는 토큰은 하나 (생성 컬럼 active_key + UNIQUE). 새로 만들기 전에 이전 것을 revoke 해야 한다
 * - active_key 는 DB가 계산하므로 매핑하지 않는다
 */
@Entity
@Table(name = "account_tokens")
public class AccountToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private TokenPurpose purpose;

    @Column(name = "token_hash", nullable = false, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "target_email", length = 255)
    private String targetEmail;

    @Column(name = "requested_ip", length = 45)
    private String requestedIp;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime expiresAt;

    @Column(name = "used_at", columnDefinition = "datetime(6)")
    private LocalDateTime usedAt;

    @Column(name = "revoked_at", columnDefinition = "datetime(6)")
    private LocalDateTime revokedAt;

    protected AccountToken() {
    }

    public AccountToken(Long userId, TokenPurpose purpose, String tokenHash, String targetEmail,
                        String requestedIp, LocalDateTime createdAt, LocalDateTime expiresAt) {
        this.userId = userId;
        this.purpose = purpose;
        this.tokenHash = tokenHash;
        this.targetEmail = targetEmail;
        this.requestedIp = requestedIp;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isUsed() { return usedAt != null; }
    public boolean isRevoked() { return revokedAt != null; }
    public boolean isExpired(LocalDateTime now) { return !now.isBefore(expiresAt); }

    public void markUsed(LocalDateTime now) { this.usedAt = now; }
    public void revoke(LocalDateTime now) { this.revokedAt = now; }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public TokenPurpose getPurpose() { return purpose; }
    public String getTokenHash() { return tokenHash; }
    public String getTargetEmail() { return targetEmail; }
    public String getRequestedIp() { return requestedIp; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getUsedAt() { return usedAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
}
