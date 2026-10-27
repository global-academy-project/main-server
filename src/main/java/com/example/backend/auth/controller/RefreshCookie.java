package com.example.backend.auth.controller;

import com.example.backend.auth.service.RefreshTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletResponse;

/**
 * refresh token 쿠키.
 *  - HttpOnly: 웹 스크립트가 읽지 못한다 (XSS로 훔칠 수 없음)
 *  - Path=/api/v1/auth: 재발급·로그아웃 요청에만 실린다
 *  - SameSite=Lax, Secure 는 app.auth.cookie-secure (https 운영에서 true)
 *  - 로그인 유지: Max-Age 를 토큰 유효 기간으로 → 브라우저를 닫아도 남는다
 *    유지 안 함: Max-Age 없음(세션 쿠키) → 브라우저를 닫으면 사라진다. 서버 쪽 토큰도 30분이면 끝
 */
@Component
public class RefreshCookie {

    public static final String NAME = "refresh_token";
    private static final String PATH = "/api/v1/auth";

    private final boolean secure;

    public RefreshCookie(@Value("${app.auth.cookie-secure:false}") boolean secure) {
        this.secure = secure;
    }

    public void write(HttpServletResponse response, RefreshTokenService.Issued issued) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(NAME, issued.rawToken())
                .httpOnly(true).secure(secure).sameSite("Lax").path(PATH);
        if (issued.remember()) {
            b.maxAge(issued.validFor());
        }
        response.addHeader(HttpHeaders.SET_COOKIE, b.build().toString());
    }

    public void clear(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(NAME, "")
                .httpOnly(true).secure(secure).sameSite("Lax").path(PATH).maxAge(0).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
