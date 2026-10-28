package com.example.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** PATCH /api/v1/users/me. 이메일·비밀번호는 별도 API로 바꾼다. */
public record UpdateMeRequest(
        @NotBlank @Size(max = 50)
        String name,

        // 숫자만, 0으로 시작하는 9~11자리. 빈 문자열이면 지운다
        @Pattern(regexp = "^$|^0[0-9]{8,10}$", message = "숫자만, 0으로 시작하는 9~11자리로 입력해주세요.")
        String phone,

        @Size(max = 100)
        String department,

        @Size(max = 50)
        String jobTitle,

        /** GET /users/me 에서 받은 값 그대로 */
        @NotNull
        Integer lockVersion
) {
}
