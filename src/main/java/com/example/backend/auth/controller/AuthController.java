package com.example.backend.auth.controller;

import com.example.backend.auth.dto.EmailRequest;
import com.example.backend.auth.dto.LoginRequest;
import com.example.backend.auth.dto.PasswordResetRequest;
import com.example.backend.auth.dto.SignupRequest;
import com.example.backend.auth.dto.TokenRequest;
import com.example.backend.auth.dto.TokenResponse;
import com.example.backend.auth.service.AuthService;
import com.example.backend.auth.service.LoginResult;
import com.example.backend.common.response.ApiResponse;
import com.example.backend.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 인증 API. /api/v1/auth/** 는 로그인 없이 호출한다 (SecurityConfig).
 * IP는 request.getRemoteAddr() 를 쓴다. 프록시(nginx) 뒤에 두면 server.forward-headers-strategy 설정이 필요하다.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshCookie refreshCookie;

    public AuthController(AuthService authService, RefreshCookie refreshCookie) {
        this.authService = authService;
        this.refreshCookie = refreshCookie;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserResponse> signup(@Valid @RequestBody SignupRequest request, HttpServletRequest http) {
        return ApiResponse.ok(authService.signup(request, http.getRemoteAddr()));
    }

    /** 로그인. access token 은 본문, refresh token 은 HttpOnly 쿠키 */
    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest http, HttpServletResponse response) {
        LoginResult result = authService.login(request, http.getHeader(HttpHeaders.USER_AGENT), http.getRemoteAddr());
        refreshCookie.write(response, result.refresh());
        return ApiResponse.ok(result.body());
    }

    /** access token 재발급. 쿠키의 refresh token 을 새것으로 바꿔 준다. 실패하면 UNAUTHORIZED(401) → 웹은 로그인 화면으로 */
    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@CookieValue(name = RefreshCookie.NAME, required = false) String token,
                                              HttpServletRequest http, HttpServletResponse response) {
        LoginResult result = authService.refresh(token, http.getHeader(HttpHeaders.USER_AGENT), http.getRemoteAddr());
        refreshCookie.write(response, result.refresh());
        return ApiResponse.ok(result.body());
    }

    /** 로그아웃: 이 기기의 세션을 끊고 쿠키를 지운다. 항상 성공 */
    @PostMapping("/logout")
    public ApiResponse<Object> logout(@CookieValue(name = RefreshCookie.NAME, required = false) String token,
                                      HttpServletResponse response) {
        authService.logout(token);
        refreshCookie.clear(response);
        return ApiResponse.okEmpty();
    }

    /** 메일 링크의 토큰으로 이메일 인증. 실패: TOKEN_INVALID(400) / TOKEN_ALREADY_USED(409) / TOKEN_EXPIRED_LINK(410) */
    @PostMapping("/email/verify")
    public ApiResponse<Object> verifyEmail(@Valid @RequestBody TokenRequest request) {
        authService.verifyEmail(request.token());
        return ApiResponse.okEmpty();
    }

    /** 인증 메일 재발송. 계정이 있든 없든 항상 성공 응답 (같은 IP에서 너무 많이 부르면 429) */
    @PostMapping("/email/verify/resend")
    public ApiResponse<Object> resendVerification(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        authService.resendVerification(request.email(), http.getRemoteAddr());
        return ApiResponse.okEmpty();
    }

    /** 비밀번호 재설정 메일. 계정이 있든 없든 항상 성공 응답 (같은 IP에서 너무 많이 부르면 429) */
    @PostMapping("/password/forgot")
    public ApiResponse<Object> forgotPassword(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        authService.forgotPassword(request.email(), http.getRemoteAddr());
        return ApiResponse.okEmpty();
    }

    /**
     * 비밀번호 재설정. 성공하면 모든 기기에서 로그아웃된다.
     * 실패: TOKEN_INVALID / TOKEN_ALREADY_USED / TOKEN_EXPIRED_LINK / PASSWORD_REUSED(400)
     */
    @PostMapping("/password/reset")
    public ApiResponse<Object> resetPassword(@Valid @RequestBody PasswordResetRequest request,
                                             HttpServletResponse response) {
        authService.resetPassword(request.token(), request.newPassword());
        refreshCookie.clear(response);
        return ApiResponse.okEmpty();
    }
}
