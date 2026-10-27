package com.example.backend.auth.service;

import com.example.backend.auth.dto.LoginRequest;
import com.example.backend.auth.dto.SignupRequest;
import com.example.backend.auth.dto.TokenResponse;
import com.example.backend.auth.entity.AccountToken;
import com.example.backend.auth.entity.RevokeReason;
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
    private final RefreshTokenService refreshTokenService;
    private final PasswordService passwordService;
    private final AuthMailSender mailSender;
    private final Clock clock;
    private final int maxFailedLogin;
    private final String webBaseUrl;
    /** 없는 이메일도 비밀번호 비교 시간을 똑같이 쓰게 하는 더미 해시 (응답 시간으로 가입 여부가 드러나지 않게) */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtProvider jwtProvider,
                       AccountTokenService tokenService, RefreshTokenService refreshTokenService,
                       PasswordService passwordService, AuthMailSender mailSender, Clock clock,
                       @Value("${app.auth.max-failed-login:10}") int maxFailedLogin,
                       @Value("${app.web.base-url:http://localhost:5173}") String webBaseUrl) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.passwordService = passwordService;
        this.mailSender = mailSender;
        this.clock = clock;
        this.maxFailedLogin = maxFailedLogin;
        this.webBaseUrl = webBaseUrl.replaceAll("/+$", "");
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    // ------------------------------------------------------------------ 가입·이메일 인증

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

    // ------------------------------------------------------------------ 로그인·세션

    /**
     * 로그인.
     * 1) 이메일 없음 / 비밀번호 틀림 → LOGIN_FAILED (어느 쪽인지 구분하지 않음). 틀리면 실패 횟수 +1
     * 2) 잠긴 계정 → ACCOUNT_LOCKED. 잠기는 순간(maxFailedLogin 번째 실패)에도 ACCOUNT_LOCKED
     * 3) 비밀번호가 맞을 때만 상태를 알려준다: 중지 → 미인증 → 승인 대기 순
     * 성공하면 access token(본문) + refresh token(쿠키). 실패 횟수가 롤백되지 않도록 BusinessException 은 롤백하지 않는다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public LoginResult login(LoginRequest request, String userAgent, String clientIp) {
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
            if (lockedNow) {
                refreshTokenService.revokeAll(user.getId(), RevokeReason.LOCKED);
            }
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
        RefreshTokenService.Issued refresh = refreshTokenService.issue(user.getId(), request.remember(), userAgent, clientIp);
        return new LoginResult(accessFor(user), refresh);
    }

    /**
     * access token 재발급. refresh token 을 새것으로 바꾸고(회전) 새 access token 을 준다.
     * 그사이 계정이 잠기거나 중지됐으면 세션을 끊고 UNAUTHORIZED.
     * 탈취 의심으로 세션을 끊은 기록이 남도록 BusinessException 은 롤백하지 않는다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public LoginResult refresh(String rawRefreshToken, String userAgent, String clientIp) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        RefreshTokenService.Issued refresh = refreshTokenService.rotate(rawRefreshToken, userAgent, clientIp);
        User user = userRepository.findById(refresh.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (user.getStatus() != UserStatus.ACTIVE) {
            refreshTokenService.revokeAll(user.getId(),
                    user.getStatus() == UserStatus.LOCKED ? RevokeReason.LOCKED : RevokeReason.ADMIN);
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return new LoginResult(accessFor(user), refresh);
    }

    /** 로그아웃: 이 기기의 세션만 끊는다. 쿠키가 없거나 이미 끊긴 세션이어도 성공으로 본다. */
    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokenService.revoke(rawRefreshToken, RevokeReason.LOGOUT);
        }
    }

    // ------------------------------------------------------------------ 비밀번호 찾기·재설정

    /**
     * 비밀번호 재설정 메일. 계정이 없거나 중지됐어도 같은 응답 (가입 여부가 드러나지 않게).
     * 승인 대기·잠금 계정도 받을 수 있다 (잠금은 재설정으로 풀린다).
     */
    @Transactional
    public void forgotPassword(String email, String clientIp) {
        tokenService.checkIpLimit(clientIp);
        userRepository.findByEmail(normalize(email))
                .filter(u -> u.getStatus() != UserStatus.INACTIVE)
                .ifPresent(u -> tokenService.issue(u.getId(), TokenPurpose.PASSWORD_RESET, null, clientIp)
                        .ifPresent(issued -> {
                            String link = webBaseUrl + "/reset-password?token=" + issued.rawToken();
                            String to = u.getEmail();
                            String name = u.getName();
                            afterCommit(() -> mailSender.sendPasswordReset(to, name, link, issued.validMinutes()));
                        }));
    }

    /**
     * 비밀번호 재설정. 최근 비밀번호 재사용 거부, 모든 기기 로그아웃, 잠금 해제.
     * 메일 링크를 눌렀다는 건 이메일 소유가 확인된 것이므로 미인증이었다면 인증도 기록한다.
     * 재사용으로 거부되면 트랜잭션이 롤백되어 링크는 다시 쓸 수 있다.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        AccountToken token = tokenService.consume(rawToken, TokenPurpose.PASSWORD_RESET);
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));
        passwordService.change(user, newPassword);
        user.verifyEmail(EmailVerifiedVia.LINK, LocalDateTime.now(clock));
    }

    // ------------------------------------------------------------------ 내부

    private TokenResponse accessFor(User user) {
        String token = jwtProvider.createToken(user.getId());
        return TokenResponse.of(token, jwtProvider.getExpirationSeconds(), UserResponse.from(user));
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
