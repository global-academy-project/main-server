package com.example.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 비밀번호 재설정: 메일 링크 토큰 + 새 비밀번호 (가입과 같은 규칙) */
public record PasswordResetRequest(
        @NotBlank @Size(max = 100)
        String token,

        @NotBlank
        @Size(min = 8, max = 64)
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).+$",
                message = "영문, 숫자, 특수문자를 각각 1개 이상 포함해야 합니다.")
        String newPassword
) {
}
