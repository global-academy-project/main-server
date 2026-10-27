package com.example.backend.auth.service;

import com.example.backend.auth.entity.RefreshToken;
import com.example.backend.auth.entity.RevokeReason;
import com.example.backend.auth.repository.RefreshTokenRepository;
import com.example.backend.auth.token.TokenCodec;
import com.example.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RefreshTokenService: 로그인 세션 발급·회전·폐기")
class RefreshTokenServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final LocalDateTime now = LocalDateTime.now(clock);
    private final AtomicLong ids = new AtomicLong(100);

    private RefreshTokenRepository repository;
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        repository = mock(RefreshTokenRepository.class);
        when(repository.saveAndFlush(any(RefreshToken.class))).thenAnswer(inv -> {
            RefreshToken t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", ids.incrementAndGet());
            return t;
        });
        service = new RefreshTokenService(repository, clock, 365, 30);
    }

    private RefreshToken stored(String raw, LocalDateTime issuedAt, LocalDateTime expiresAt) {
        RefreshToken t = new RefreshToken(7L, TokenCodec.sha256Hex(raw), issuedAt, expiresAt, "UA", "ip");
        ReflectionTestUtils.setField(t, "id", 1L);
        when(repository.findByTokenHash(TokenCodec.sha256Hex(raw))).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    @DisplayName("발급: 유지 체크 365일 / 미체크 30분, DB엔 해시만")
    void issue_durations() {
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);

        RefreshTokenService.Issued remember = service.issue(7L, true, "UA", "ip");
        RefreshTokenService.Issued shortOne = service.issue(7L, false, "UA", "ip");

        verify(repository, org.mockito.Mockito.times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues().get(0).getExpiresAt()).isEqualTo(now.plusDays(365));
        assertThat(saved.getAllValues().get(0).isRemember()).isTrue();
        assertThat(saved.getAllValues().get(1).getExpiresAt()).isEqualTo(now.plusMinutes(30));
        assertThat(saved.getAllValues().get(1).isRemember()).isFalse();
        assertThat(saved.getAllValues().get(0).getTokenHash()).isEqualTo(TokenCodec.sha256Hex(remember.rawToken()));
        assertThat(remember.validFor()).isEqualTo(Duration.ofDays(365));
        assertThat(shortOne.remember()).isFalse();
    }

    @Test
    @DisplayName("회전: 옛 토큰은 ROTATED + 새 토큰 id, 유지 여부는 옛 토큰을 따른다 (30분짜리는 다시 30분)")
    void rotate_ok() {
        RefreshToken old = stored("OLD", now.minusMinutes(20), now.plusMinutes(10));

        RefreshTokenService.Issued issued = service.rotate("OLD", "UA", "ip");

        assertThat(old.getRevokeReason()).isEqualTo(RevokeReason.ROTATED);
        assertThat(old.getReplacedBy()).isEqualTo(101L);
        assertThat(old.getRevokedAt()).isEqualTo(now);
        assertThat(issued.remember()).isFalse();
        assertThat(issued.validFor()).isEqualTo(Duration.ofMinutes(30));
        assertThat(issued.userId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("회전 실패: 없는 토큰·만료·로그아웃된 토큰은 UNAUTHORIZED")
    void rotate_rejects() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());
        expectCode(() -> service.rotate("NONE", "UA", "ip"), ErrorCode.UNAUTHORIZED);

        stored("EXPIRED", now.minusMinutes(40), now.minusMinutes(10));
        expectCode(() -> service.rotate("EXPIRED", "UA", "ip"), ErrorCode.UNAUTHORIZED);

        RefreshToken out = stored("OUT", now.minusMinutes(5), now.plusMinutes(25));
        out.revoke(RevokeReason.LOGOUT, now.minusMinutes(1));
        expectCode(() -> service.rotate("OUT", "UA", "ip"), ErrorCode.UNAUTHORIZED);

        verify(repository, never()).revokeAllActive(any(), any(), any());
    }

    @Test
    @DisplayName("탈취 의심: 회전된 지 10초 넘은 토큰이 다시 오면 그 사용자 세션을 모두 끊는다")
    void rotate_reuseDetected() {
        RefreshToken old = stored("OLD", now.minusMinutes(20), now.plusMinutes(10));
        old.rotateTo(99L, now.minusMinutes(1));

        expectCode(() -> service.rotate("OLD", "UA", "ip"), ErrorCode.UNAUTHORIZED);
        verify(repository).revokeAllActive(7L, RevokeReason.REUSE_DETECTED, now);
    }

    @Test
    @DisplayName("동시 요청: 회전된 지 10초 안이면 이 요청만 거절하고 세션은 그대로 둔다 (탭 2개 동시 재발급)")
    void rotate_graceForConcurrentTabs() {
        RefreshToken old = stored("OLD", now.minusMinutes(20), now.plusMinutes(10));
        old.rotateTo(99L, now.minusSeconds(3));

        expectCode(() -> service.rotate("OLD", "UA", "ip"), ErrorCode.UNAUTHORIZED);
        verify(repository, never()).revokeAllActive(any(), any(), any());
    }

    @Test
    @DisplayName("로그아웃: 그 토큰만 LOGOUT, 없는 토큰은 조용히 넘어간다")
    void revoke() {
        RefreshToken t = stored("RT", now.minusMinutes(1), now.plusMinutes(29));
        service.revoke("RT", RevokeReason.LOGOUT);
        assertThat(t.getRevokeReason()).isEqualTo(RevokeReason.LOGOUT);

        when(repository.findByTokenHash(TokenCodec.sha256Hex("NONE"))).thenReturn(Optional.empty());
        service.revoke("NONE", RevokeReason.LOGOUT);
    }
}
