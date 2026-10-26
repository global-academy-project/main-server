package com.example.backend.auth.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SignupRequest / LoginRequest: 입력 검증 규칙")
class SignupRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> invalidFields(Object request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath).map(Object::toString).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("정상 입력은 위반이 없다")
    void valid() {
        assertThat(invalidFields(new SignupRequest("user@example.com", "Passw0rd!", "홍길동"))).isEmpty();
    }

    @ParameterizedTest(name = "비밀번호 \"{0}\"")
    @ValueSource(strings = {"Pw0rd!!", "abcdefgh", "12345678", "abcd1234", "abcdefg!", "1234567!"})
    @DisplayName("비밀번호는 8자 이상이면서 영문·숫자·특수문자를 모두 포함해야 한다")
    void password_complexity(String password) {
        assertThat(invalidFields(new SignupRequest("user@example.com", password, "홍길동"))).containsExactly("password");
    }

    @Test
    @DisplayName("경계: 비밀번호 64자는 허용, 65자는 거부")
    void password_lengthBoundary() {
        String ok64 = "Aa1!" + "x".repeat(60);
        String bad65 = ok64 + "x";

        assertThat(invalidFields(new SignupRequest("user@example.com", ok64, "홍길동"))).isEmpty();
        assertThat(invalidFields(new SignupRequest("user@example.com", bad65, "홍길동"))).containsExactly("password");
    }

    @ParameterizedTest(name = "이메일 \"{0}\"")
    @ValueSource(strings = {"", "   ", "no-at-sign", "a@", "@b.com"})
    @DisplayName("이메일이 비었거나 형식이 틀리면 거부")
    void email_invalid(String email) {
        assertThat(invalidFields(new SignupRequest(email, "Passw0rd!", "홍길동"))).contains("email");
    }

    @Test
    @DisplayName("이메일 255자 초과 거부")
    void email_tooLong() {
        String email = "a".repeat(250) + "@b.com";   // 256자

        assertThat(invalidFields(new SignupRequest(email, "Passw0rd!", "홍길동"))).contains("email");
    }

    @Test
    @DisplayName("이름: 공백만 있으면 거부, 50자는 허용, 51자는 거부")
    void name_rules() {
        assertThat(invalidFields(new SignupRequest("user@example.com", "Passw0rd!", "   "))).containsExactly("name");
        assertThat(invalidFields(new SignupRequest("user@example.com", "Passw0rd!", "가".repeat(50)))).isEmpty();
        assertThat(invalidFields(new SignupRequest("user@example.com", "Passw0rd!", "가".repeat(51)))).containsExactly("name");
    }

    @Test
    @DisplayName("필드가 null 이면 모두 거부")
    void nulls() {
        assertThat(invalidFields(new SignupRequest(null, null, null))).containsExactlyInAnyOrder("email", "password", "name");
    }

    @Test
    @DisplayName("로그인 요청: 이메일/비밀번호가 비어 있으면 거부")
    void login_blank() {
        assertThat(invalidFields(new LoginRequest("", " "))).containsExactlyInAnyOrder("email", "password");
        assertThat(invalidFields(new LoginRequest("a@b.com", "x"))).isEmpty();
    }
}
