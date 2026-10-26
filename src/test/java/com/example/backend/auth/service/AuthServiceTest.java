package com.example.backend.auth.service;

import com.example.backend.auth.dto.LoginRequest;
import com.example.backend.auth.dto.SignupRequest;
import com.example.backend.auth.dto.TokenResponse;
import com.example.backend.auth.jwt.JwtProvider;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.user.dto.UserResponse;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AuthService: 회원가입 / 로그인")
class AuthServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtProvider jwtProvider;
    private AuthService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtProvider = mock(JwtProvider.class);
        service = new AuthService(userRepository, passwordEncoder, jwtProvider);
    }

    private User storedUser() {
        User u = new User("user@example.com", "HASH", "홍길동");
        ReflectionTestUtils.setField(u, "id", 7L);
        return u;
    }

    // ---------- signup ----------

    @Test
    @DisplayName("가입: 이메일은 앞뒤 공백 제거 + 소문자, 이름은 trim, 비밀번호는 암호화해서 저장")
    void signup_normalizesAndEncodes() {
        when(passwordEncoder.encode("Passw0rd!")).thenReturn("ENCODED");

        UserResponse res = service.signup(new SignupRequest("  User@Example.COM ", "Passw0rd!", "  홍길동 "));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("user@example.com");
        assertThat(saved.getValue().getName()).isEqualTo("홍길동");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("ENCODED").isNotEqualTo("Passw0rd!");
        verify(userRepository).existsByEmail("user@example.com");
        assertThat(res.email()).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("이미 가입된 이메일(대소문자·공백만 다른 경우 포함)은 EMAIL_DUPLICATED, 저장하지 않는다")
    void signup_duplicate() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        expectCode(() -> service.signup(new SignupRequest(" USER@example.com", "Passw0rd!", "a")),
                ErrorCode.EMAIL_DUPLICATED);

        verify(userRepository, never()).saveAndFlush(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("동시 가입으로 DB unique 제약에 걸리면(경쟁 상태) EMAIL_DUPLICATED 로 변환")
    void signup_raceCondition() {
        when(passwordEncoder.encode(any())).thenReturn("ENCODED");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("dup"));

        expectCode(() -> service.signup(new SignupRequest("a@b.com", "Passw0rd!", "a")), ErrorCode.EMAIL_DUPLICATED);
    }

    // ---------- login ----------

    @Test
    @DisplayName("로그인 성공: 토큰, Bearer, 만료 시간, 사용자 정보를 돌려준다 (이메일 정규화 후 조회)")
    void login_success() {
        User user = storedUser();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Passw0rd!", "HASH")).thenReturn(true);
        when(jwtProvider.createToken(7L)).thenReturn("JWT");
        when(jwtProvider.getExpirationSeconds()).thenReturn(3600L);

        TokenResponse res = service.login(new LoginRequest("  USER@Example.com ", "Passw0rd!"));

        assertThat(res.accessToken()).isEqualTo("JWT");
        assertThat(res.tokenType()).isEqualTo("Bearer");
        assertThat(res.expiresIn()).isEqualTo(3600L);
        assertThat(res.user().userId()).isEqualTo(7L);
        assertThat(res.user().email()).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("없는 이메일 / 틀린 비밀번호는 같은 LOGIN_FAILED (어느 쪽이 틀렸는지 알려주지 않음), 토큰 미발급")
    void login_failure_sameForBothCases() {
        when(userRepository.findByEmail("none@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(storedUser()));
        when(passwordEncoder.matches("wrong", "HASH")).thenReturn(false);

        var unknownEmail = expectCode(() -> service.login(new LoginRequest("none@example.com", "x")), ErrorCode.LOGIN_FAILED);
        var wrongPassword = expectCode(() -> service.login(new LoginRequest("user@example.com", "wrong")), ErrorCode.LOGIN_FAILED);

        assertThat(unknownEmail.getMessage()).isEqualTo(wrongPassword.getMessage());
        verify(jwtProvider, never()).createToken(any());
    }
}
