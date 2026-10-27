package com.example.backend.auth.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 로그인 세션. 테이블 규칙은 V2 refresh_tokens.
 * - 원문은 HttpOnly 쿠키로만, DB엔 SHA-256 (ck_rt_hash)
 * - 재발급마다 새 토큰으로 바꾸고(ROTATED + replaced_by), 이미 바뀐 토큰이 다시 오면 탈취로 본다
 * - "로그인 유지"는 별도 컬럼 없이 유효 기간 길이로 구분한다 (하루 넘으면 유지)
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    private static final Duration REMEMBER_THRESHOLD = Duration.ofDays(1);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at", columnDefinition = "datetime(6)")
    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "revoke_reason", length = 20)
    private RevokeReason revokeReason;

    @Column(name = "replaced_by")
    private Long replacedBy;

    @Column(name = "user_agent", length = 200)
    private String userAgent;

    @Column(length = 45)
    private String ip;

    protected RefreshToken() {
    }

    public RefreshToken(Long userId, String tokenHash, LocalDateTime issuedAt, LocalDateTime expiresAt,
                        String userAgent, String ip) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent == null ? null : userAgent.substring(0, Math.min(200, userAgent.length()));
        this.ip = ip;
    }

    public boolean isRemember() {
        return Duration.between(issuedAt, expiresAt).compareTo(REMEMBER_THRESHOLD) > 0;
    }

    public boolean isRevoked() { return revokedAt != null; }
    public boolean isExpired(LocalDateTime now) { return !now.isBefore(expiresAt); }

    /** 재발급: 사유와 새 토큰 id를 함께 기록해야 DB CHECK(ck_rt_rotated)를 통과한다 */
    public void rotateTo(Long newTokenId, LocalDateTime now) {
        this.revokedAt = now;
        this.revokeReason = RevokeReason.ROTATED;
        this.replacedBy = newTokenId;
    }

    public void revoke(RevokeReason reason, LocalDateTime now) {
        if (reason == RevokeReason.ROTATED) {
            throw new IllegalArgumentException("ROTATED 는 rotateTo 로만 기록한다");
        }
        if (this.revokedAt == null) {
            this.revokedAt = now;
            this.revokeReason = reason;
        }
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public RevokeReason getRevokeReason() { return revokeReason; }
    public Long getReplacedBy() { return replacedBy; }
}
