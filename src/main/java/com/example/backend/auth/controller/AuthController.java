package com.example.backend.auth.controller;

import com.example.backend.auth.dto.EmailRequest;
import com.example.backend.auth.dto.LoginRequest;
import com.example.backend.auth.dto.SignupRequest;
import com.example.backend.auth.dto.TokenRequest;
import com.example.backend.auth.dto.TokenResponse;
import com.example.backend.auth.service.AuthService;
import com.example.backend.common.response.ApiResponse;
import com.example.backend.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserResponse> signup(@Valid @RequestBody SignupRequest request, HttpServletRequest http) {
        return ApiResponse.ok(authService.signup(request, http.getRemoteAddr()));
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
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
}
