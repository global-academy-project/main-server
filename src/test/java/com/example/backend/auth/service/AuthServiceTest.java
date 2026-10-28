package com.example.backend.auth.service;

import com.example.backend.auth.dto.LoginRequest;
import com.example.backend.auth.dto.SignupRequest;
import com.example.backend.auth.dto.TokenResponse;
import com.example.backend.auth.entity.AccountToken;
import com.example.backend.auth.entity.RevokeReason;
import com.example.backend.auth.entity.TokenPurpose;
import com.example.backend.auth.jwt.JwtProvider;
import com.example.backend.auth.mail.AuthMailSender;
import com.example.backend.auth.mail.WebLinks;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.user.dto.UserResponse;
import com.example.backend.user.entity.EmailVerifiedVia;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserStatus;
import com.example.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AuthService: 가입 / 로그인 / 이메일 인증")
class AuthServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T01:00:00Z"), KST);
    private final LocalDateTime now = LocalDateTime.now(clock);

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtProvider jwtProvider;
    private AccountTokenService tokenService;
    private AuthMailSender mailSender;
    private RefreshTokenService refreshTokenService;
    private PasswordService passwordService;
    private AuthService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtProvider = mock(JwtProvider.class);
        tokenService = mock(AccountTokenService.class);
        mailSender = mock(AuthMailSender.class);
        refreshTokenService = mock(RefreshTokenService.class);
        passwordService = mock(PasswordService.class);
        when(passwordEncoder.encode("dummy-password-for-timing")).thenReturn("DUMMY");
        when(refreshTokenService.issue(anyLong(), anyBoolean(), any(), any()))
                .thenAnswer(inv -> new RefreshTokenService.Issued(inv.getArgument(0), "RT", inv.getArgument(1), Duration.ofMinutes(30)));
        service = new AuthService(userRepository, passwordEncoder, jwtProvider, tokenService, refreshTokenService,
                passwordService, mailSender, new WebLinks("http://web.test/"), clock, 10);
    }

    private User storedUser(UserStatus status, boolean verified) {
        User u = new User("user@example.com", "HASH", "홍길동");
        ReflectionTestUtils.setField(u, "id", 7L);
        if (verified) {
            u.verifyEmail(EmailVerifiedVia.LINK, now);
        }
        ReflectionTestUtils.setField(u, "status", status);
        return u;
    }

    private void givenUser(User u) {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(u));
    }

    // ---------- signup ----------

    @Test
    @DisplayName("가입: 이메일 정규화·비밀번호 암호화, PENDING·미인증으로 저장하고 인증 메일을 보낸다")
    void signup_savesPendingAndSendsMail() {
        when(passwordEncoder.encode("Passw0rd!")).thenReturn("ENCODED");
        when(tokenService.issue(any(), eq(TokenPurpose.EMAIL_VERIFY), isNull(), eq("1.2.3.4")))
                .thenReturn(Optional.of(new AccountTokenService.Issued("RAW", 10)));

        UserResponse res = service.signup(
                new SignupRequest("  User@Example.COM ", "Passw0rd!", "  홍길동 ", "01012345678", " 안전보건팀 ", ""),
                "1.2.3.4");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        User u = saved.getValue();
        assertThat(u.getEmail()).isEqualTo("user@example.com");
        assertThat(u.getName()).isEqualTo("홍길동");
        assertThat(u.getPasswordHash()).isEqualTo("ENCODED");
        assertThat(u.getStatus()).isEqualTo(UserStatus.PENDING);
        assertThat(u.isEmailVerified()).isFalse();
        assertThat(u.getPhone()).isEqualTo("01012345678");
        assertThat(u.getDepartment()).isEqualTo("안전보건팀");
        assertThat(u.getJobTitle()).as("빈 문자열은 null 로").isNull();
        assertThat(res.status()).isEqualTo(UserStatus.PENDING);
        verify(mailSender).sendEmailVerification("user@example.com", "홍길동",
                "http://web.test/verify-email?token=RAW", 10);
    }

    @Test
    @DisplayName("이미 가입된 이메일은 EMAIL_DUPLICATED, 저장·메일 없음")
    void signup_duplicate() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        expectCode(() -> service.signup(new SignupRequest(" USER@example.com", "Passw0rd!", "a"), "ip"),
                ErrorCode.EMAIL_DUPLICATED);

        verify(userRepository, never()).saveAndFlush(any());
        verify(mailSender, never()).sendEmailVerification(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("동시 가입으로 DB unique 제약에 걸리면 EMAIL_DUPLICATED")
    void signup_raceCondition() {
        when(passwordEncoder.encode(any())).thenReturn("ENCODED");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("dup"));

        expectCode(() -> service.signup(new SignupRequest("a@b.com", "Passw0rd!", "a"), "ip"), ErrorCode.EMAIL_DUPLICATED);
    }

    // ---------- login ----------

    @Test
    @DisplayName("로그인 성공(ACTIVE·인증됨): 토큰 발급, 실패 횟수 초기화, 마지막 로그인 기록")
    void login_success() {
        User user = storedUser(UserStatus.ACTIVE, true);
        ReflectionTestUtils.setField(user, "failedLoginCount", 3);
        givenUser(user);
        when(passwordEncoder.matches("Passw0rd!", "HASH")).thenReturn(true);
        when(jwtProvider.createToken(7L)).thenReturn("JWT");
        when(jwtProvider.getExpirationSeconds()).thenReturn(3600L);

        LoginResult result = service.login(new LoginRequest("  USER@Example.com ", "Passw0rd!", true), "UA", "ip");
        TokenResponse res = result.body();

        assertThat(result.refresh().rawToken()).isEqualTo("RT");
        verify(refreshTokenService).issue(7L, true, "UA", "ip");

        assertThat(res.accessToken()).isEqualTo("JWT");
        assertThat(res.tokenType()).isEqualTo("Bearer");
        assertThat(res.mustChangePassword()).isFalse();
        assertThat(res.user().userId()).isEqualTo(7L);
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getLastLoginAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("없는 이메일 / 틀린 비밀번호는 같은 LOGIN_FAILED, 토큰 미발급. 없는 이메일도 비밀번호 비교를 한다(시간 동일)")
    void login_failure_sameForBothCases() {
        when(userRepository.findByEmail("none@example.com")).thenReturn(Optional.empty());
        givenUser(storedUser(UserStatus.ACTIVE, true));
        when(passwordEncoder.matches("wrong", "HASH")).thenReturn(false);

        var unknownEmail = expectCode(() -> service.login(new LoginRequest("none@example.com", "x"), "UA", "ip").body(), ErrorCode.LOGIN_FAILED);
        var wrongPassword = expectCode(() -> service.login(new LoginRequest("user@example.com", "wrong"), "UA", "ip").body(), ErrorCode.LOGIN_FAILED);

        assertThat(unknownEmail.getMessage()).isEqualTo(wrongPassword.getMessage());
        verify(passwordEncoder).matches("x", "DUMMY");
        verify(jwtProvider, never()).createToken(any());
    }

    @Test
    @DisplayName("10번째 실패에서 잠기고 ACCOUNT_LOCKED. 잠긴 뒤에는 맞는 비밀번호도 ACCOUNT_LOCKED")
    void login_locksAfterTenFailures() {
        User user = storedUser(UserStatus.ACTIVE, true);
        givenUser(user);
        when(passwordEncoder.matches("wrong", "HASH")).thenReturn(false);
        when(passwordEncoder.matches("Passw0rd!", "HASH")).thenReturn(true);

        for (int i = 1; i <= 9; i++) {
            expectCode(() -> service.login(new LoginRequest("user@example.com", "wrong"), "UA", "ip").body(), ErrorCode.LOGIN_FAILED);
        }
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);

        expectCode(() -> service.login(new LoginRequest("user@example.com", "wrong"), "UA", "ip").body(), ErrorCode.ACCOUNT_LOCKED);
        assertThat(user.getStatus()).isEqualTo(UserStatus.LOCKED);
        assertThat(user.getFailedLoginCount()).isEqualTo(10);

        expectCode(() -> service.login(new LoginRequest("user@example.com", "Passw0rd!"), "UA", "ip").body(), ErrorCode.ACCOUNT_LOCKED);
        verify(jwtProvider, never()).createToken(any());
    }

    @Test
    @DisplayName("승인 전(PENDING) 계정은 실패가 쌓여도 잠그지 않는다 (어차피 로그인 불가)")
    void login_pendingNotLocked() {
        User user = storedUser(UserStatus.PENDING, false);
        givenUser(user);
        when(passwordEncoder.matches("wrong", "HASH")).thenReturn(false);

        for (int i = 0; i < 12; i++) {
            expectCode(() -> service.login(new LoginRequest("user@example.com", "wrong"), "UA", "ip").body(), ErrorCode.LOGIN_FAILED);
        }
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING);
    }

    @Test
    @DisplayName("비밀번호가 맞을 때 상태 안내: 미인증 → EMAIL_NOT_VERIFIED, 인증·승인 대기 → ACCOUNT_PENDING, 중지 → ACCOUNT_INACTIVE")
    void login_statusMessages() {
        when(passwordEncoder.matches("Passw0rd!", "HASH")).thenReturn(true);

        givenUser(storedUser(UserStatus.PENDING, false));
        expectCode(() -> service.login(new LoginRequest("user@example.com", "Passw0rd!"), "UA", "ip").body(), ErrorCode.EMAIL_NOT_VERIFIED);

        givenUser(storedUser(UserStatus.PENDING, true));
        expectCode(() -> service.login(new LoginRequest("user@example.com", "Passw0rd!"), "UA", "ip").body(), ErrorCode.ACCOUNT_PENDING);

        givenUser(storedUser(UserStatus.INACTIVE, true));
        expectCode(() -> service.login(new LoginRequest("user@example.com", "Passw0rd!"), "UA", "ip").body(), ErrorCode.ACCOUNT_INACTIVE);

        verify(jwtProvider, never()).createToken(any());
    }

    @Test
    @DisplayName("첫 관리자처럼 비밀번호 변경이 필요하면 응답에 mustChangePassword=true")
    void login_mustChangePassword() {
        User admin = User.bootstrapAdmin("user@example.com", "HASH", now);
        ReflectionTestUtils.setField(admin, "id", 1L);
        givenUser(admin);
        when(passwordEncoder.matches("Admin1234!", "HASH")).thenReturn(true);
        when(jwtProvider.createToken(1L)).thenReturn("JWT");

        TokenResponse res = service.login(new LoginRequest("user@example.com", "Admin1234!"), "UA", "ip").body();

        assertThat(res.mustChangePassword()).isTrue();
    }

    // ---------- email verify ----------

    @Test
    @DisplayName("이메일 인증: 토큰을 쓰면 인증 시각·LINK 기록, 상태는 PENDING 그대로")
    void verifyEmail_marksVerified() {
        User user = storedUser(UserStatus.PENDING, false);
        AccountToken token = new AccountToken(7L, TokenPurpose.EMAIL_VERIFY, "h".repeat(64), null, null, now, now.plusMinutes(10));
        when(tokenService.consume("RAW", TokenPurpose.EMAIL_VERIFY)).thenReturn(token);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        service.verifyEmail("RAW");

        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getEmailVerifiedVia()).isEqualTo(EmailVerifiedVia.LINK);
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING);
    }

    @Test
    @DisplayName("재발송: 없는 이메일·이미 인증된 계정은 조용히 넘어간다 (메일 없음, 예외 없음)")
    void resend_silentForUnknownOrVerified() {
        when(userRepository.findByEmail("none@example.com")).thenReturn(Optional.empty());
        service.resendVerification("none@example.com", "ip");

        givenUser(storedUser(UserStatus.PENDING, true));
        service.resendVerification("user@example.com", "ip");

        verify(tokenService, never()).issue(any(), any(), any(), any());
        verify(mailSender, never()).sendEmailVerification(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("재발송: 미인증 PENDING 계정이면 새 토큰으로 메일을 보낸다")
    void resend_sendsForUnverified() {
        givenUser(storedUser(UserStatus.PENDING, false));
        when(tokenService.issue(7L, TokenPurpose.EMAIL_VERIFY, null, "ip"))
                .thenReturn(Optional.of(new AccountTokenService.Issued("NEW", 10)));

        service.resendVerification("User@example.com", "ip");

        verify(tokenService).checkIpLimit("ip");
        verify(mailSender).sendEmailVerification(eq("user@example.com"), anyString(),
                eq("http://web.test/verify-email?token=NEW"), eq(10L));
    }

    @Test
    @DisplayName("재발송: 같은 IP 한도를 넘으면 계정이 없어도 TOO_MANY_REQUESTS (가입 여부와 무관)")
    void resend_ipLimit() {
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.TOO_MANY_REQUESTS))
                .when(tokenService).checkIpLimit("ip");

        expectCode(() -> service.resendVerification("none@example.com", "ip"), ErrorCode.TOO_MANY_REQUESTS);
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    @DisplayName("10번째 실패로 잠기면 그 사용자의 로그인 세션도 모두 끊는다")
    void login_lockRevokesSessions() {
        User user = storedUser(UserStatus.ACTIVE, true);
        ReflectionTestUtils.setField(user, "failedLoginCount", 9);
        givenUser(user);
        when(passwordEncoder.matches("wrong", "HASH")).thenReturn(false);

        expectCode(() -> service.login(new LoginRequest("user@example.com", "wrong"), "UA", "ip"), ErrorCode.ACCOUNT_LOCKED);
        verify(refreshTokenService).revokeAll(7L, RevokeReason.LOCKED);
    }

    // ---------- refresh / logout ----------

    @Test
    @DisplayName("재발급: 회전한 새 refresh token 과 새 access token")
    void refresh_ok() {
        when(refreshTokenService.rotate("OLD", "UA", "ip"))
                .thenReturn(new RefreshTokenService.Issued(7L, "NEW", false, Duration.ofMinutes(30)));
        when(userRepository.findById(7L)).thenReturn(Optional.of(storedUser(UserStatus.ACTIVE, true)));
        when(jwtProvider.createToken(7L)).thenReturn("JWT2");

        LoginResult r = service.refresh("OLD", "UA", "ip");

        assertThat(r.body().accessToken()).isEqualTo("JWT2");
        assertThat(r.refresh().rawToken()).isEqualTo("NEW");
    }

    @Test
    @DisplayName("재발급: 쿠키 없음 → UNAUTHORIZED, 그사이 잠긴 계정 → 세션 모두 끊고 UNAUTHORIZED")
    void refresh_rejects() {
        expectCode(() -> service.refresh(null, "UA", "ip"), ErrorCode.UNAUTHORIZED);

        when(refreshTokenService.rotate("OLD", "UA", "ip"))
                .thenReturn(new RefreshTokenService.Issued(7L, "NEW", false, Duration.ofMinutes(30)));
        when(userRepository.findById(7L)).thenReturn(Optional.of(storedUser(UserStatus.LOCKED, true)));

        expectCode(() -> service.refresh("OLD", "UA", "ip"), ErrorCode.UNAUTHORIZED);
        verify(refreshTokenService).revokeAll(7L, RevokeReason.LOCKED);
        verify(jwtProvider, never()).createToken(any());
    }

    @Test
    @DisplayName("로그아웃: 쿠키가 있으면 그 세션만 LOGOUT 으로 끊고, 없으면 아무것도 안 한다")
    void logout() {
        service.logout("RT");
        verify(refreshTokenService).revoke("RT", RevokeReason.LOGOUT);

        service.logout(null);
        verify(refreshTokenService, org.mockito.Mockito.times(1)).revoke(any(), any());
    }

    // ---------- forgot / reset ----------

    @Test
    @DisplayName("비밀번호 찾기: 계정이 있으면 재설정 메일, 없거나 중지 계정이면 조용히 넘어간다")
    void forgot() {
        givenUser(storedUser(UserStatus.LOCKED, true));
        when(tokenService.issue(7L, TokenPurpose.PASSWORD_RESET, null, "ip"))
                .thenReturn(Optional.of(new AccountTokenService.Issued("RESET", 10)));
        service.forgotPassword("user@example.com", "ip");
        verify(mailSender).sendPasswordReset("user@example.com", "홍길동", "http://web.test/reset-password?token=RESET", 10);

        when(userRepository.findByEmail("none@example.com")).thenReturn(Optional.empty());
        service.forgotPassword("none@example.com", "ip");
        when(userRepository.findByEmail("off@example.com")).thenReturn(Optional.of(storedUser(UserStatus.INACTIVE, true)));
        service.forgotPassword("off@example.com", "ip");

        verify(tokenService, org.mockito.Mockito.times(1)).issue(any(), eq(TokenPurpose.PASSWORD_RESET), any(), any());
        verify(tokenService, org.mockito.Mockito.times(3)).checkIpLimit("ip");
    }

    @Test
    @DisplayName("재설정: 토큰을 쓰고 공통 비밀번호 변경 규칙을 적용, 메일 링크로 이메일 소유도 확인된 것으로 본다")
    void reset() {
        User user = storedUser(UserStatus.PENDING, false);
        AccountToken token = new AccountToken(7L, TokenPurpose.PASSWORD_RESET, "h".repeat(64), null, null, now, now.plusMinutes(10));
        when(tokenService.consume("RESET", TokenPurpose.PASSWORD_RESET)).thenReturn(token);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        service.resetPassword("RESET", "NewPassw0rd!");

        verify(passwordService).change(user, "NewPassw0rd!");
        assertThat(user.isEmailVerified()).isTrue();
    }

    // ---------- 로그인한 사용자의 비밀번호·이메일 변경 (3단계) ----------

    @Test
    @DisplayName("비밀번호 변경: 현재 비밀번호 확인 → 공통 규칙 → 이 기기엔 같은 유지 방식으로 새 세션")
    void changeOwnPassword() {
        User user = storedUser(UserStatus.ACTIVE, true);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(refreshTokenService.isRemember("RT")).thenReturn(true);
        when(jwtProvider.createToken(7L)).thenReturn("JWT");

        LoginResult r = service.changeOwnPassword(7L, "Old0000!", "NewPassw0rd!", "RT", "UA", "ip");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(passwordService, refreshTokenService);
        order.verify(passwordService).checkCurrent(user, "Old0000!");
        order.verify(passwordService).change(user, "NewPassw0rd!");
        order.verify(refreshTokenService).issue(7L, true, "UA", "ip");
        assertThat(r.body().accessToken()).isEqualTo("JWT");
    }

    @Test
    @DisplayName("비밀번호 변경: 로그인 안 함 → UNAUTHORIZED, 현재 비밀번호 틀림 → PASSWORD_MISMATCH (바꾸지 않음)")
    void changeOwnPassword_rejects() {
        expectCode(() -> service.changeOwnPassword(null, "a", "b", null, "UA", "ip"), ErrorCode.UNAUTHORIZED);

        User user = storedUser(UserStatus.ACTIVE, true);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.PASSWORD_MISMATCH))
                .when(passwordService).checkCurrent(user, "wrong");

        expectCode(() -> service.changeOwnPassword(7L, "wrong", "NewPassw0rd!", null, "UA", "ip"), ErrorCode.PASSWORD_MISMATCH);
        verify(passwordService, never()).change(any(), any());
    }

    @Test
    @DisplayName("이메일 변경 확인: 토큰의 새 주소로 바꾸고 인증 시각 갱신, 그사이 다른 사람이 쓰면 EMAIL_DUPLICATED")
    void confirmEmailChange() {
        User user = storedUser(UserStatus.ACTIVE, true);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        AccountToken token = new AccountToken(7L, TokenPurpose.EMAIL_CHANGE, "h".repeat(64), "new@example.com", null, now, now.plusMinutes(10));
        when(tokenService.consume("CHG", TokenPurpose.EMAIL_CHANGE)).thenReturn(token);

        service.confirmEmailChange("CHG");
        assertThat(user.getEmail()).isEqualTo("new@example.com");
        assertThat(user.getEmailVerifiedAt()).isEqualTo(now);

        when(userRepository.existsByEmail("new@example.com")).thenReturn(true);
        expectCode(() -> service.confirmEmailChange("CHG"), ErrorCode.EMAIL_DUPLICATED);
    }
}
