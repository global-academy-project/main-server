package com.example.backend.auth.dto;

import com.example.backend.user.dto.UserResponse;

/**
 * 로그인 응답. mustChangePassword 가 true 면 웹은 /change-password 로 보낸다.
 */
public record TokenResponse(String accessToken, String tokenType, long expiresIn,
                            boolean mustChangePassword, UserResponse user) {

    public static TokenResponse of(String accessToken, long expiresIn, UserResponse user) {
        return new TokenResponse(accessToken, "Bearer", expiresIn, user.mustChangePassword(), user);
    }
}
