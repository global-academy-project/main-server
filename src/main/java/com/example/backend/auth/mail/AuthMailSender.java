package com.example.backend.auth.mail;

/**
 * 인증 관련 메일 발송. 구현은 app.mail.mode 로 고른다.
 *   log  (기본): 메일 대신 서버 로그에 링크를 찍는다. SMTP 없이 개발할 때
 *   smtp      : spring.mail.* 설정으로 실제 발송
 */
public interface AuthMailSender {

    /** 이메일 인증 링크 (웹 /verify-email?token=) */
    void sendEmailVerification(String to, String name, String link, long validMinutes);

    /** 비밀번호 재설정 링크 (웹 /reset-password?token=) */
    void sendPasswordReset(String to, String name, String link, long validMinutes);

    /** 이메일 변경 확인 링크. 새 주소로 보낸다 (웹 /verify-email-change?token=) */
    void sendEmailChange(String to, String name, String link, long validMinutes);
}
