package com.example.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 인증 메일 재발송 등 이메일 하나만 받는 요청 */
public record EmailRequest(
        @NotBlank @Email @Size(max = 255)
        String email
) {
}
