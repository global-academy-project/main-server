package com.example.backend.auth.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** 운영용: SMTP 로 발송. spring.mail.host/port/username/password 와 app.mail.from 이 필요하다. */
@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "smtp")
public class SmtpAuthMailSender implements AuthMailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpAuthMailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpAuthMailSender(JavaMailSender mailSender, @Value("${app.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendEmailVerification(String to, String name, String link, long validMinutes) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject("[산업현장 안전관리 AI] 이메일 인증");
        msg.setText(name + "님, 아래 링크를 눌러 이메일 인증을 마쳐 주세요.\n\n"
                + link + "\n\n"
                + "링크는 " + validMinutes + "분 동안 한 번만 쓸 수 있습니다.\n"
                + "가입한 적이 없다면 이 메일은 무시하세요.");
        try {
            mailSender.send(msg);
        } catch (MailException e) {
            // 발송 실패로 가입·재발송 자체를 실패시키지 않는다. 사용자는 재발송을 누르거나 관리자 대면 확인을 받으면 된다
            log.error("인증 메일 발송 실패 to={}", to, e);
        }
    }
}
