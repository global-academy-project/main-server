package com.example.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 메일 링크의 토큰 원문 (base64url 43자) */
public record TokenRequest(
        @NotBlank @Size(max = 100)
        String token
) {
}
