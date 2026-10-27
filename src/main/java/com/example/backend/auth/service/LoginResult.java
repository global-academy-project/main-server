package com.example.backend.auth.service;

import com.example.backend.auth.dto.TokenResponse;

/** 로그인·재발급 결과: 응답 본문 + 쿠키로 내보낼 refresh token */
public record LoginResult(TokenResponse body, RefreshTokenService.Issued refresh) {
}
