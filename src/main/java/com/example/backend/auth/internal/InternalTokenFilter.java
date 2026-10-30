package com.example.backend.auth.internal;

import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.response.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * AI 서버 → Spring 내부 API(/internal/**) 인증. JWT 대신 헤더 X-Internal-Token 만 본다.
 *  - 내부 API 전용 보안 체인(SecurityConfig.internalFilterChain)에만 들어가며, 그 체인의 모든 요청을 검사한다.
 *    경로를 이 필터가 직접 판정하지 않는다 (원본 URI 와 디코딩된 경로가 달라 검사를 건너뛰는 일이 없게)
 *  - 토큰은 환경 변수 INTERNAL_API_TOKEN (app.internal.token). 비어 있거나 32자 미만이면 서버가 뜨지 않는다
 *  - 비교는 상수 시간 (MessageDigest.isEqual) → 응답 시간으로 토큰을 추측할 수 없게
 *  - 맞으면 ROLE_INTERNAL 인증을 채우고, 없거나 틀리면 401 UNAUTHORIZED
 * 방화벽·내부망 제한은 추가 방어일 뿐, 토큰 검사를 대신하지 않는다.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";
    public static final String ROLE = "INTERNAL";
    private static final int MIN_LENGTH = 32;

    private final byte[] expected;

    public InternalTokenFilter(String token) {
        if (token == null || token.isBlank() || token.length() < MIN_LENGTH) {
            throw new IllegalStateException(
                    "app.internal.token(INTERNAL_API_TOKEN)은 " + MIN_LENGTH + "자 이상이어야 합니다.");
        }
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        if (given == null || !MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8))) {
            ErrorResponseWriter.write(response, ErrorCode.UNAUTHORIZED);
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "ai-model", null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
        chain.doFilter(request, response);
    }
}
