package com.example.backend.auth.service;

import com.example.backend.auth.entity.PasswordHistory;
import com.example.backend.auth.entity.RevokeReason;
import com.example.backend.auth.repository.PasswordHistoryRepository;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.user.entity.EmailVerifiedVia;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PasswordService: 비밀번호 변경 공통 규칙")
class PasswordServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final LocalDateTime now = LocalDateTime.now(clock);

    private PasswordHistoryRepository historyRepository;
    private RefreshTokenService refreshTokenService;
    private PasswordEncoder encoder;
    private PasswordService service;
    private User user;

    @BeforeEach
    void setUp() {
        historyRepository = mock(PasswordHistoryRepository.class);
        refreshTokenService = mock(RefreshTokenService.class);
        encoder = mock(PasswordEncoder.class);
        service = new PasswordService(historyRepository, refreshTokenService, encoder, clock, 3);

        user = new User("user@example.com", "CURRENT_HASH", "홍길동");
        ReflectionTestUtils.setField(user, "id", 7L);
        user.verifyEmail(EmailVerifiedVia.LINK, now);
        ReflectionTestUtils.setField(user, "status", UserStatus.LOCKED);
        ReflectionTestUtils.setField(user, "failedLoginCount", 10);
        ReflectionTestUtils.setField(user, "mustChangePassword", true);
        when(historyRepository.findByUserIdOrderByCreatedAtDescIdDesc(7L, PageRequest.of(0, 3)))
                .thenReturn(List.of(new PasswordHistory(7L, "OLD1_HASH", now.minusDays(30))));
    }

    @Test
    @DisplayName("변경: 지금 해시를 이력에 남기고 새 해시로, 잠금·실패 횟수·강제 변경 해제, 세션 모두 끊기")
    void change_ok() {
        when(encoder.encode("NewPassw0rd!")).thenReturn("NEW_HASH");

        service.change(user, "NewPassw0rd!");

        ArgumentCaptor<PasswordHistory> saved = ArgumentCaptor.forClass(PasswordHistory.class);
        verify(historyRepository).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("CURRENT_HASH");
        assertThat(user.getPasswordHash()).isEqualTo("NEW_HASH");
        assertThat(user.getPasswordChangedAt()).isEqualTo(now);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.isMustChangePassword()).isFalse();
        verify(refreshTokenService).revokeAll(7L, RevokeReason.PASSWORD_CHANGED);
    }

    @Test
    @DisplayName("지금 비밀번호나 최근 비밀번호와 같으면 PASSWORD_REUSED, 아무것도 바꾸지 않는다")
    void change_reused() {
        when(encoder.matches("Same0000!", "CURRENT_HASH")).thenReturn(true);
        expectCode(() -> service.change(user, "Same0000!"), ErrorCode.PASSWORD_REUSED);

        when(encoder.matches("Old1Pass!", "OLD1_HASH")).thenReturn(true);
        expectCode(() -> service.change(user, "Old1Pass!"), ErrorCode.PASSWORD_REUSED);

        assertThat(user.getPasswordHash()).isEqualTo("CURRENT_HASH");
        verify(historyRepository, never()).save(any());
        verify(refreshTokenService, never()).revokeAll(any(), any());
    }
}
