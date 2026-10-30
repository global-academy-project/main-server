package com.example.backend.auth.jwt;

import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.response.ErrorResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/** 인증 실패 시 공통 에러 봉투(401)를 직접 쓴다. 필터 단계 예외는 @RestControllerAdvice 가 받지 못한다. */
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object attr = request.getAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE);
        ErrorCode code = attr instanceof ErrorCode c ? c : ErrorCode.UNAUTHORIZED;

        ErrorResponseWriter.write(response, code);
    }
}
