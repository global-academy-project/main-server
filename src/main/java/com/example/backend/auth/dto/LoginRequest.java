package com.example.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password,
        // 로그인 유지. 2단계(refresh token)에서 사용한다. 지금은 받기만 한다
        Boolean rememberMe
) {

    public LoginRequest(String email, String password) {
        this(email, password, false);
    }

    public boolean remember() {
        return Boolean.TRUE.equals(rememberMe);
    }
}
