package com.example.backend.auth.jwt;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JwtProvider: 토큰 발급 / 검증")
class JwtProviderTest {

    private static final String SECRET = "unit-test-secret-unit-test-secret-0123456789";   // 테스트 전용 값
    private static final String OTHER_SECRET = "another-test-secret-another-test-secret-9876543210";

    @Test
    @DisplayName("발급한 토큰에서 같은 userId 를 꺼낼 수 있다")
    void roundTrip() {
        JwtProvider provider = new JwtProvider(SECRET, 3600);

        assertThat(provider.getUserId(provider.createToken(42L))).isEqualTo(42L);
        assertThat(provider.getExpirationSeconds()).isEqualTo(3600L);
    }

    @Test
    @DisplayName("시크릿이 32바이트 미만이면 서버 시작 단계에서 실패")
    void shortSecret_rejected() {
        assertThatThrownBy(() -> new JwtProvider("too-short", 3600))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32바이트");
    }

    @Test
    @DisplayName("경계: 정확히 32바이트 시크릿은 허용")
    void secret_exactly32Bytes_allowed() {
        JwtProvider provider = new JwtProvider("a".repeat(32), 60);

        assertThat(provider.getUserId(provider.createToken(1L))).isEqualTo(1L);
    }

    @Test
    @DisplayName("만료된 토큰은 ExpiredJwtException")
    void expiredToken() {
        JwtProvider provider = new JwtProvider(SECRET, -10);

        assertThatThrownBy(() -> provider.getUserId(provider.createToken(1L))).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("다른 시크릿으로 서명된 토큰은 거부")
    void otherSecret_rejected() {
        String token = new JwtProvider(OTHER_SECRET, 3600).createToken(1L);

        assertThatThrownBy(() -> new JwtProvider(SECRET, 3600).getUserId(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("payload 는 그대로 두고 서명만 바꾼(위조) 토큰은 거부")
    void tamperedSignature_rejected() {
        JwtProvider provider = new JwtProvider(SECRET, 3600);
        String[] mine = provider.createToken(1L).split("\\.");
        String[] other = new JwtProvider(OTHER_SECRET, 3600).createToken(1L).split("\\.");
        String forged = mine[0] + "." + mine[1] + "." + other[2];

        assertThatThrownBy(() -> provider.getUserId(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("형식이 깨진 문자열은 JwtException, 빈 문자열은 IllegalArgumentException")
    void malformedToken() {
        JwtProvider provider = new JwtProvider(SECRET, 3600);

        assertThatThrownBy(() -> provider.getUserId("not-a-jwt")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> provider.getUserId("")).isInstanceOf(IllegalArgumentException.class);
    }
}
