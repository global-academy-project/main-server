package com.example.backend.auth.internal;

import com.example.backend.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * AI 서버 → Spring 내부 API(/internal/**) 인증. JWT 대신 헤더 X-Internal-Token 만 본다.
 *  - 토큰은 환경 변수 INTERNAL_API_TOKEN (app.internal.token). 비어 있거나 32자 미만이면 서버가 뜨지 않는다
 *  - 비교는 상수 시간 (MessageDigest.isEqual) → 응답 시간으로 토큰을 추측할 수 없게
 *  - 토큰이 없거나 틀리면 401 UNAUTHORIZED
 * /internal/** 는 compose 내부망에서만 열어 둔다 (통합 때 설정).
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";
    public static final String PATH_PREFIX = "/internal/";
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
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        if (given == null || !MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8))) {
            ErrorCode code = ErrorCode.UNAUTHORIZED;
            response.setStatus(code.getStatus().value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"error\":{\"code\":\"" + code.name()
                    + "\",\"message\":\"" + code.getDefaultMessage() + "\",\"details\":[]}}");
            return;
        }
        chain.doFilter(request, response);
    }
}
