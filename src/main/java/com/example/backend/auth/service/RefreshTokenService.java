package com.example.backend.auth.service;

import com.example.backend.auth.entity.RefreshToken;
import com.example.backend.auth.entity.RevokeReason;
import com.example.backend.auth.repository.RefreshTokenRepository;
import com.example.backend.auth.token.TokenCodec;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 로그인 세션(refresh token). 호출하는 쪽 트랜잭션 안에서 동작한다.
 *  - 로그인 유지 체크: app.auth.refresh-remember-days (기본 365일). 재발급할 때마다 다시 늘어나므로 사실상 로그아웃 전까지
 *  - 체크 안 함: app.auth.refresh-short-minutes (기본 30분). 재발급할 때마다 다시 30분 → 30분 동안 아무것도 안 하면 끝
 */
@Service
// 여기서 던진 BusinessException 이 트랜잭션을 rollback-only 로 만들지 않게 한다 (롤백 여부는 호출한 쪽이 정함)
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = BusinessException.class)
public class RefreshTokenService {

    /** 이미 회전된 토큰이 이 시간 안에 다시 오면 탈취가 아니라 동시 요청(탭 2개 등)으로 본다 */
    private static final Duration ROTATION_GRACE = Duration.ofSeconds(10);

    /** 발급 결과: 쿠키에 넣을 원문, 유지 여부(쿠키 만료 설정용), 유효 기간 */
    public record Issued(Long userId, String rawToken, boolean remember, Duration validFor) {
    }

    private final RefreshTokenRepository repository;
    private final Clock clock;
    private final Duration rememberFor;
    private final Duration shortFor;

    public RefreshTokenService(RefreshTokenRepository repository, Clock clock,
                               @Value("${app.auth.refresh-remember-days:365}") long rememberDays,
                               @Value("${app.auth.refresh-short-minutes:30}") long shortMinutes) {
        this.repository = repository;
        this.clock = clock;
        this.rememberFor = Duration.ofDays(rememberDays);
        this.shortFor = Duration.ofMinutes(shortMinutes);
    }

    public Issued issue(Long userId, boolean remember, String userAgent, String ip) {
        return create(userId, remember, userAgent, ip).issued();
    }

    private record Created(RefreshToken entity, Issued issued) {
    }

    private Created create(Long userId, boolean remember, String userAgent, String ip) {
        LocalDateTime now = LocalDateTime.now(clock);
        Duration validFor = remember ? rememberFor : shortFor;
        String raw = TokenCodec.newRawToken();
        RefreshToken entity = repository.saveAndFlush(
                new RefreshToken(userId, TokenCodec.sha256Hex(raw), now, now.plus(validFor), userAgent, ip));
        return new Created(entity, new Issued(userId, raw, remember, validFor));
    }

    /**
     * 재발급(회전). 옛 토큰은 ROTATED 로 막고 새 토큰을 준다. 유지 여부는 옛 토큰을 따른다.
     * 실패는 모두 UNAUTHORIZED (웹은 로그인 화면으로).
     * 이미 회전된 토큰이 다시 오면 탈취로 보고 그 사용자의 세션을 모두 끊는다 (단, 10초 안이면 동시 요청으로 보고 그 요청만 거절).
     * 세션을 끊은 기록이 남아야 하므로 호출하는 쪽은 BusinessException 에 롤백하지 않아야 한다.
     */
    public Issued rotate(String rawToken, String userAgent, String ip) {
        LocalDateTime now = LocalDateTime.now(clock);
        RefreshToken old = repository.findByTokenHash(TokenCodec.sha256Hex(rawToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        if (old.isRevoked()) {
            boolean recentlyRotated = old.getRevokeReason() == RevokeReason.ROTATED
                    && Duration.between(old.getRevokedAt(), now).compareTo(ROTATION_GRACE) <= 0;
            if (old.getRevokeReason() == RevokeReason.ROTATED && !recentlyRotated) {
                repository.revokeAllActive(old.getUserId(), RevokeReason.REUSE_DETECTED, now);
            }
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (old.isExpired(now)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        Created created = create(old.getUserId(), old.isRemember(), userAgent, ip);
        old.rotateTo(created.entity().getId(), now);
        return created.issued();
    }

    /** 로그아웃. 없거나 이미 끊긴 토큰이어도 조용히 넘어간다 */
    public void revoke(String rawToken, RevokeReason reason) {
        LocalDateTime now = LocalDateTime.now(clock);
        repository.findByTokenHash(TokenCodec.sha256Hex(rawToken)).ifPresent(t -> t.revoke(reason, now));
    }

    public void revokeAll(Long userId, RevokeReason reason) {
        repository.revokeAllActive(userId, reason, LocalDateTime.now(clock));
    }
}
