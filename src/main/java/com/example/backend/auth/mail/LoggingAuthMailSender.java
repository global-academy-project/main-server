package com.example.backend.auth.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 개발용: 메일을 보내지 않고 링크를 로그로 남긴다. 운영에서는 app.mail.mode=smtp 로 바꾼다. */
@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "log", matchIfMissing = true)
public class LoggingAuthMailSender implements AuthMailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingAuthMailSender.class);

    @Override
    public void sendEmailVerification(String to, String name, String link, long validMinutes) {
        log.info("[메일-개발모드] 이메일 인증 to={} ({}분 유효) link={}", to, validMinutes, link);
    }

    @Override
    public void sendPasswordReset(String to, String name, String link, long validMinutes) {
        log.info("[메일-개발모드] 비밀번호 재설정 to={} ({}분 유효) link={}", to, validMinutes, link);
    }
}
