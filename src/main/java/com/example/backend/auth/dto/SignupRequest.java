package com.example.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank @Email @Size(max = 255)
        String email,

        // 8~64자, 영문 + 숫자 + 특수문자 각 1개 이상
        @NotBlank
        @Size(min = 8, max = 64)
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).+$",
                message = "영문, 숫자, 특수문자를 각각 1개 이상 포함해야 합니다.")
        String password,

        @NotBlank @Size(min = 1, max = 50)
        String name
) {
}
