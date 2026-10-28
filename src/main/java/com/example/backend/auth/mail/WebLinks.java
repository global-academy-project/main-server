package com.example.backend.auth.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 메일에 넣는 웹 화면 주소. 웹 주소는 설정(app.web.base-url ← WEB_BASE_URL)에서만 온다. 코드에 주소·포트를 두지 않는다.
 * 웹 화면 경로가 바뀌면 여기만 고친다.
 */
@Component
public class WebLinks {

    private final String baseUrl;

    public WebLinks(@Value("${app.web.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /** 이메일 인증 (가입) */
    public String verifyEmail(String token) {
        return baseUrl + "/verify-email?token=" + token;
    }

    /** 비밀번호 재설정 */
    public String resetPassword(String token) {
        return baseUrl + "/reset-password?token=" + token;
    }

    /** 이메일 변경 확인 (새 주소로 보냄) */
    public String verifyEmailChange(String token) {
        return baseUrl + "/verify-email-change?token=" + token;
    }
}
