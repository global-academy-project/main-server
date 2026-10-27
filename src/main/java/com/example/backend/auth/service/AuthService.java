package com.example.backend.auth.service;

import com.example.backend.auth.dto.LoginRequest;
import com.example.backend.auth.dto.SignupRequest;
import com.example.backend.auth.dto.TokenResponse;
import com.example.backend.auth.entity.AccountToken;
import com.example.backend.auth.entity.TokenPurpose;
import com.example.backend.auth.jwt.JwtProvider;
import com.example.backend.auth.mail.AuthMailSender;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.user.dto.UserResponse;
import com.example.backend.user.entity.EmailVerifiedVia;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserStatus;
import com.example.backend.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final AccountTokenService tokenService;
    private final AuthMailSender mailSender;
    private final Clock clock;
    private final int maxFailedLogin;
    private final String webBaseUrl;
    /** 없는 이메일도 비밀번호 비교 시간을 똑같이 쓰게 하는 더미 해시 (응답 시간으로 가입 여부가 드러나지 않게) */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtProvider jwtProvider,
                       AccountTokenService tokenService, AuthMailSender mailSender, Clock clock,
                       @Value("${app.auth.max-failed-login:10}") int maxFailedLogin,
                       @Value("${app.web.base-url:http://localhost:5173}") String webBaseUrl) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
        this.tokenService = tokenService;
        this.mailSender = mailSender;
        this.clock = clock;
        this.maxFailedLogin = maxFailedLogin;
        this.webBaseUrl = webBaseUrl.replaceAll("/+$", "");
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    /** 가입: PENDING 으로 저장하고 인증 메일을 보낸다. 바로 로그인은 안 된다 (이메일 인증 + 관리자 승인 후). */
    @Transactional
    public UserResponse signup(SignupRequest request, String clientIp) {
        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATED);
        }
        User user = new User(email, passwordEncoder.encode(request.password()), request.name().trim());
        user.updateProfile(request.phone(), request.department(), request.jobTitle());
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // 동시 가입으로 unique 제약에 걸린 경우
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATED);
        }
        sendVerificationMail(user, clientIp);
        return UserResponse.from(user);
    }

    /**
     * 로그인.
     * 1) 이메일 없음 / 비밀번호 틀림 → LOGIN_FAILED (어느 쪽인지 구분하지 않음). 틀리면 실패 횟수 +1
     * 2) 잠긴 계정 → ACCOUNT_LOCKED. 잠기는 순간(maxFailedLogin 번째 실패)에도 ACCOUNT_LOCKED
     * 3) 비밀번호가 맞을 때만 상태를 알려준다: 중지 → 미인증 → 승인 대기 순
     * 실패 횟수가 롤백되지 않도록 BusinessException 은 롤백하지 않는다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(normalize(request.email())).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        if (user.getStatus() == UserStatus.LOCKED) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            boolean lockedNow = user.recordLoginFailure(maxFailedLogin);
            throw new BusinessException(lockedNow ? ErrorCode.ACCOUNT_LOCKED : ErrorCode.LOGIN_FAILED);
        }

        if (user.getStatus() == UserStatus.INACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_INACTIVE);
        }
        if (!user.isEmailVerified()) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        if (user.getStatus() == UserStatus.PENDING) {
            throw new BusinessException(ErrorCode.ACCOUNT_PENDING);
        }

        user.recordLoginSuccess(LocalDateTime.now(clock));
        String token = jwtProvider.createToken(user.getId());
        // TODO(2단계): request.remember() 에 따라 refresh token 발급 (유지 = 로그아웃 전까지, 미유지 = 30분)
        return TokenResponse.of(token, jwtProvider.getExpirationSeconds(), UserResponse.from(user));
    }

    /** 메일 링크로 이메일 인증. 상태는 PENDING 그대로 (ACTIVE 는 관리자 승인 때). */
    @Transactional
    public void verifyEmail(String rawToken) {
        AccountToken token = tokenService.consume(rawToken, TokenPurpose.EMAIL_VERIFY);
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));
        user.verifyEmail(EmailVerifiedVia.LINK, LocalDateTime.now(clock));
    }

    /**
     * 인증 메일 재발송. 계정이 없거나 이미 인증됐어도 같은 응답 (가입 여부가 드러나지 않게).
     * IP 한도는 계정 유무와 상관없이 적용, 사용자별 한도를 넘으면 조용히 보내지 않는다.
     */
    @Transactional
    public void resendVerification(String email, String clientIp) {
        tokenService.checkIpLimit(clientIp);
        userRepository.findByEmail(normalize(email))
                .filter(u -> !u.isEmailVerified())
                .filter(u -> u.getStatus() == UserStatus.PENDING)
                .ifPresent(u -> sendVerificationMail(u, clientIp));
    }

    private void sendVerificationMail(User user, String clientIp) {
        tokenService.issue(user.getId(), TokenPurpose.EMAIL_VERIFY, null, clientIp).ifPresent(issued -> {
            String link = webBaseUrl + "/verify-email?token=" + issued.rawToken();
            String to = user.getEmail();
            String name = user.getName();
            // 메일은 DB에 토큰이 확실히 저장된 뒤(커밋 후)에 보낸다
            afterCommit(() -> mailSender.sendEmailVerification(to, name, link, issued.validMinutes()));
        });
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
