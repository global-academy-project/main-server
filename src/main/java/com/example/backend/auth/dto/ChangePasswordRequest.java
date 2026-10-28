package com.example.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 로그인한 사용자의 비밀번호 변경 (내 정보, 임시 비밀번호 강제 변경 공통) */
public record ChangePasswordRequest(
        @NotBlank
        String currentPassword,

        @NotBlank
        @Size(min = 8, max = 64)
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).+$",
                message = "영문, 숫자, 특수문자를 각각 1개 이상 포함해야 합니다.")
        String newPassword
) {
}
