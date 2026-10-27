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
        String name,

        // 선택. 숫자만, 0으로 시작하는 9~11자리 (DB ck_users_phone 과 같은 규칙). 빈 문자열은 미입력으로 본다
        @Pattern(regexp = "^$|^0[0-9]{8,10}$", message = "숫자만, 0으로 시작하는 9~11자리로 입력해주세요.")
        String phone,

        // 선택
        @Size(max = 100)
        String department,

        // 선택
        @Size(max = 50)
        String jobTitle
) {

        /** 선택 항목 없이 가입 (기존 호출·테스트 호환) */
        public SignupRequest(String email, String password, String name) {
                this(email, password, name, null, null, null);
        }
}
