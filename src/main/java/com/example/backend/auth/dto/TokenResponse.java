package com.example.backend.auth.dto;

import com.example.backend.user.dto.UserResponse;

public record TokenResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {

    public static TokenResponse of(String accessToken, long expiresIn, UserResponse user) {
        return new TokenResponse(accessToken, "Bearer", expiresIn, user);
    }
}
