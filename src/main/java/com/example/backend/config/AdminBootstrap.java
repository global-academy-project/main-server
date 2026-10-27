package com.example.backend.config;

import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserRole;
import com.example.backend.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 첫 관리자 생성. ADMIN 이 한 명도 없고 ADMIN_EMAIL·ADMIN_PASSWORD 가 있을 때만 동작한다.
 * 생성된 관리자는 이메일 인증(관리자 확인)·ACTIVE 상태이고, 첫 로그인 때 비밀번호를 바꿔야 한다.
 * 이미 같은 이메일로 가입된 일반 회원이 있으면 건드리지 않고 경고만 남긴다.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String email;
    private final String password;

    public AdminBootstrap(UserRepository userRepository, PasswordEncoder passwordEncoder, Clock clock,
                          @Value("${app.admin.email:}") String email,
                          @Value("${app.admin.password:}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRole(UserRole.ADMIN)) {
            return;
        }
        if (email.isBlank() || password.isBlank()) {
            log.warn("관리자 계정이 없습니다. ADMIN_EMAIL, ADMIN_PASSWORD 를 설정하고 다시 시작하면 첫 관리자가 만들어집니다.");
            return;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(normalized)) {
            log.warn("ADMIN_EMAIL({}) 로 이미 가입된 일반 회원이 있어 첫 관리자를 만들지 않았습니다.", normalized);
            return;
        }
        userRepository.save(User.bootstrapAdmin(normalized, passwordEncoder.encode(password), LocalDateTime.now(clock)));
        log.info("첫 관리자 {} 를 만들었습니다. 첫 로그인 때 비밀번호를 바꿔야 합니다.", normalized);
    }
}
