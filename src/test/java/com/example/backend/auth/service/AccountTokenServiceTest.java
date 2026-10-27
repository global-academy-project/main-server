package com.example.backend.auth.service;

import com.example.backend.auth.entity.AccountToken;
import com.example.backend.auth.entity.TokenPurpose;
import com.example.backend.auth.repository.AccountTokenRepository;
import com.example.backend.auth.token.TokenCodec;
import com.example.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AccountTokenService: 메일 링크 토큰 발급·사용")
class AccountTokenServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final LocalDateTime now = LocalDateTime.now(clock);

    private AccountTokenRepository repository;
    private AccountTokenService service;

    @BeforeEach
    void setUp() {
        repository = mock(AccountTokenRepository.class);
        service = new AccountTokenService(repository, clock, 10, 5, 30);
    }

    private AccountToken stored(String raw, TokenPurpose purpose, LocalDateTime expiresAt) {
        AccountToken t = new AccountToken(7L, purpose, TokenCodec.sha256Hex(raw), null, null, now.minusMinutes(1), expiresAt);
        when(repository.findByTokenHash(TokenCodec.sha256Hex(raw))).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    @DisplayName("발급: 이전 토큰을 먼저 폐기하고, 원문이 아닌 SHA-256(64자 hex)을 10분 만료로 저장한다")
    void issue_revokesThenStoresHash() {
        Optional<AccountTokenService.Issued> issued = service.issue(7L, TokenPurpose.EMAIL_VERIFY, null, "1.2.3.4");

        assertThat(issued).isPresent();
        String raw = issued.get().rawToken();
        assertThat(raw).hasSize(43);

        InOrder order = inOrder(repository);
        order.verify(repository).revokeActive(7L, TokenPurpose.EMAIL_VERIFY, now);
        ArgumentCaptor<AccountToken> saved = ArgumentCaptor.forClass(AccountToken.class);
        order.verify(repository).save(saved.capture());

        AccountToken t = saved.getValue();
        assertThat(t.getTokenHash()).matches("^[0-9a-f]{64}$").isEqualTo(TokenCodec.sha256Hex(raw)).isNotEqualTo(raw);
        assertThat(t.getCreatedAt()).isEqualTo(now);
        assertThat(t.getExpiresAt()).isEqualTo(now.plusMinutes(10));
        assertThat(t.getRequestedIp()).isEqualTo("1.2.3.4");
    }

    @Test
    @DisplayName("한 사람에게 1시간 5회를 넘으면 발급하지 않고 빈 값 (예외 없이)")
    void issue_userLimit() {
        when(repository.countByUserIdAndPurposeAndCreatedAtAfter(7L, TokenPurpose.EMAIL_VERIFY, now.minusHours(1))).thenReturn(5L);

        assertThat(service.issue(7L, TokenPurpose.EMAIL_VERIFY, null, "ip")).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("같은 IP 1시간 30회 이상이면 TOO_MANY_REQUESTS")
    void ipLimit() {
        when(repository.countByRequestedIpAndCreatedAtAfter("ip", now.minusHours(1))).thenReturn(30L);
        expectCode(() -> service.checkIpLimit("ip"), ErrorCode.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("사용: 유효하면 사용 처리")
    void consume_ok() {
        AccountToken t = stored("RAW", TokenPurpose.EMAIL_VERIFY, now.plusMinutes(5));

        assertThat(service.consume("RAW", TokenPurpose.EMAIL_VERIFY)).isSameAs(t);
        assertThat(t.getUsedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("사용 실패: 없음·다른 용도 → TOKEN_INVALID, 이미 사용 → TOKEN_ALREADY_USED, 만료·폐기 → TOKEN_EXPIRED_LINK")
    void consume_failures() {
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.empty());
        expectCode(() -> service.consume("NONE", TokenPurpose.EMAIL_VERIFY), ErrorCode.TOKEN_INVALID);

        stored("RESET", TokenPurpose.PASSWORD_RESET, now.plusMinutes(5));
        expectCode(() -> service.consume("RESET", TokenPurpose.EMAIL_VERIFY), ErrorCode.TOKEN_INVALID);

        AccountToken used = stored("USED", TokenPurpose.EMAIL_VERIFY, now.plusMinutes(5));
        used.markUsed(now.minusSeconds(10));
        expectCode(() -> service.consume("USED", TokenPurpose.EMAIL_VERIFY), ErrorCode.TOKEN_ALREADY_USED);

        stored("OLD", TokenPurpose.EMAIL_VERIFY, now);   // 만료 시각 == 지금 → 만료
        expectCode(() -> service.consume("OLD", TokenPurpose.EMAIL_VERIFY), ErrorCode.TOKEN_EXPIRED_LINK);

        AccountToken revoked = stored("REVOKED", TokenPurpose.EMAIL_VERIFY, now.plusMinutes(5));
        revoked.revoke(now.minusSeconds(5));
        expectCode(() -> service.consume("REVOKED", TokenPurpose.EMAIL_VERIFY), ErrorCode.TOKEN_EXPIRED_LINK);
    }
}
