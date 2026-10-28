package com.example.backend.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/users/me/email-change. 새 주소로 확인 메일을 보낸다 (현재 비밀번호 확인 필요). */
public record EmailChangeRequest(
        @NotBlank @Email @Size(max = 255)
        String newEmail,

        @NotBlank
        String currentPassword
) {
}
