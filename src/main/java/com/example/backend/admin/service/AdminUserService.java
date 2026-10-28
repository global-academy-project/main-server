package com.example.backend.admin.service;

import com.example.backend.admin.dto.AdminUserResponse;
import com.example.backend.admin.dto.ApproveRequest;
import com.example.backend.admin.dto.SiteGrant;
import com.example.backend.admin.dto.TemporaryPasswordResponse;
import com.example.backend.auth.entity.RevokeReason;
import com.example.backend.auth.service.PasswordService;
import com.example.backend.auth.service.RefreshTokenService;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.site.entity.SiteMember;
import com.example.backend.site.repository.SiteMemberRepository;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.site.service.SiteAccessService;
import com.example.backend.user.entity.EmailVerifiedVia;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserRole;
import com.example.backend.user.entity.UserStatus;
import com.example.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 관리자 메뉴: 가입 승인, 현장 권한, 역할, 잠금 해제, 임시 비밀번호, 대면 이메일 확인, 사용 중지·재개.
 * 호출 경로(/api/v1/admin/**)는 SecurityConfig 에서 ADMIN 만 통과한다.
 * 보호 규칙: 자기 자신을 중지·강등할 수 없고, 사용 중인 관리자가 한 명은 남아야 한다.
 */
@Service
public class AdminUserService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final SiteRepository siteRepository;
    private final SiteMemberRepository siteMemberRepository;
    private final SiteAccessService siteAccessService;
    private final PasswordService passwordService;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;

    public AdminUserService(UserRepository userRepository, SiteRepository siteRepository,
                            SiteMemberRepository siteMemberRepository, SiteAccessService siteAccessService,
                            PasswordService passwordService, RefreshTokenService refreshTokenService, Clock clock) {
        this.userRepository = userRepository;
        this.siteRepository = siteRepository;
        this.siteMemberRepository = siteMemberRepository;
        this.siteAccessService = siteAccessService;
        this.passwordService = passwordService;
        this.refreshTokenService = refreshTokenService;
        this.clock = clock;
    }

    /** 회원 목록. status 를 주면 그 상태만 (예: PENDING = 승인 대기). 최근 가입 순 */
    @Transactional(readOnly = true)
    public List<AdminUserResponse> list(UserStatus status) {
        List<User> users = status == null
                ? userRepository.findAllByOrderByCreatedAtDesc()
                : userRepository.findByStatusOrderByCreatedAtDesc(status);
        return users.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public AdminUserResponse get(Long userId) {
        return toResponse(load(userId));
    }

    /** 가입 승인: 역할 + 현장 권한을 주고 ACTIVE 로. 이메일 인증 전이면 INVALID_STATE */
    @Transactional
    public AdminUserResponse approve(Long adminId, Long userId, ApproveRequest request) {
        User user = load(userId);
        UserRole role = request.roleOrDefault();
        if (role == UserRole.MEMBER && request.sites().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "일반 회원은 현장 권한을 1개 이상 줘야 합니다.");
        }
        if (user.getStatus() != UserStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "승인 대기 중인 회원이 아닙니다.");
        }
        if (!user.isEmailVerified()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이메일 인증 전입니다. 메일 확인을 기다리거나 대면 확인 후 승인하세요.");
        }
        applyGrants(adminId, user.getId(), request.sites());   // 현장 확인이 먼저 (없는 현장이면 상태를 바꾸기 전에 거부)
        user.approve(role);
        return toResponse(user);
    }

    /** 현장 권한을 이 목록으로 통째로 바꾼다. 목록에 없는 현장은 권한 회수(행은 남기고 INACTIVE) */
    @Transactional
    public AdminUserResponse replaceSites(Long adminId, Long userId, List<SiteGrant> grants) {
        User user = load(userId);
        applyGrants(adminId, user.getId(), grants);
        return toResponse(user);
    }

    @Transactional
    public AdminUserResponse changeRole(Long adminId, Long userId, UserRole role) {
        User user = load(userId);
        if (user.getRole() == UserRole.ADMIN && role != UserRole.ADMIN) {
            guardNotSelf(adminId, userId, "자기 자신의 관리자 권한은 뺄 수 없습니다.");
            guardOtherActiveAdmin(user);
        }
        user.changeRole(role);
        return toResponse(user);
    }

    @Transactional
    public AdminUserResponse unlock(Long userId) {
        User user = load(userId);
        user.unlock();
        return toResponse(user);
    }

    /** 임시 비밀번호 발급. 응답으로 한 번만 보여 준다. 본인은 다음 로그인 때 바꿔야 하고, 기존 세션은 모두 끊긴다 */
    @Transactional
    public TemporaryPasswordResponse issueTemporaryPassword(Long userId) {
        User user = load(userId);
        String temp = newTemporaryPassword();
        passwordService.assignTemporary(user, temp);
        return new TemporaryPasswordResponse(temp);
    }

    /** 메일이 막힌 환경: 관리자가 본인을 직접 확인하고 이메일 인증 처리 */
    @Transactional
    public AdminUserResponse verifyEmailInPerson(Long userId) {
        User user = load(userId);
        user.verifyEmail(EmailVerifiedVia.ADMIN, LocalDateTime.now(clock));
        return toResponse(user);
    }

    /** 사용 중지 (퇴사 등). 즉시 모든 세션이 끊기고, 남은 access token 도 다음 요청부터 거부된다 */
    @Transactional
    public AdminUserResponse deactivate(Long adminId, Long userId) {
        User user = load(userId);
        guardNotSelf(adminId, userId, "자기 자신은 사용 중지할 수 없습니다.");
        if (user.getRole() == UserRole.ADMIN) {
            guardOtherActiveAdmin(user);
        }
        user.deactivate();
        refreshTokenService.revokeAll(user.getId(), RevokeReason.ADMIN);
        return toResponse(user);
    }

    @Transactional
    public AdminUserResponse reactivate(Long userId) {
        User user = load(userId);
        try {
            user.reactivate();
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_STATE, e.getMessage());
        }
        return toResponse(user);
    }

    // ------------------------------------------------------------------ 내부

    private void applyGrants(Long adminId, Long userId, List<SiteGrant> grants) {
        Set<Long> requested = new HashSet<>();
        for (SiteGrant g : grants) {
            if (!requested.add(g.siteId())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 현장이 두 번 들어 있습니다.");
            }
        }
        Set<Long> activeSites = siteRepository.findAllById(requested).stream()
                .filter(s -> s.isActive()).map(s -> s.getId()).collect(Collectors.toSet());
        if (!activeSites.containsAll(requested)) {
            throw new BusinessException(ErrorCode.SITE_NOT_FOUND);
        }

        Map<Long, SiteMember> existing = siteMemberRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(SiteMember::getSiteId, Function.identity()));
        List<SiteMember> toSave = new ArrayList<>();
        for (SiteGrant g : grants) {
            SiteMember m = existing.get(g.siteId());
            if (m == null) {
                toSave.add(new SiteMember(userId, g.siteId(), g.role(), adminId));
            } else {
                m.grant(g.role(), adminId);
            }
        }
        existing.values().stream().filter(m -> !requested.contains(m.getSiteId()) && m.isActive())
                .forEach(SiteMember::revoke);
        siteMemberRepository.saveAll(toSave);
    }

    private void guardNotSelf(Long adminId, Long userId, String message) {
        if (adminId != null && adminId.equals(userId)) {
            throw new BusinessException(ErrorCode.INVALID_STATE, message);
        }
    }

    /** 이 관리자를 빼도 사용 중인 관리자가 남는지 */
    private void guardOtherActiveAdmin(User admin) {
        long activeAdmins = userRepository.countByRoleAndStatus(UserRole.ADMIN, UserStatus.ACTIVE);
        long remaining = admin.getStatus() == UserStatus.ACTIVE ? activeAdmins - 1 : activeAdmins;
        if (remaining < 1) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "사용 중인 관리자가 최소 한 명은 있어야 합니다.");
        }
    }

    private User load(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private AdminUserResponse toResponse(User user) {
        return AdminUserResponse.of(user, siteAccessService.accessibleSites(user));
    }

    /** 12자: 영문 대·소문자, 숫자, 특수문자를 각각 1개 이상 (헷갈리는 글자 0 O 1 l I 제외) */
    static String newTemporaryPassword() {
        String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String lower = "abcdefghijkmnpqrstuvwxyz";
        String digits = "23456789";
        String special = "!@#$%^&*";
        String all = upper + lower + digits + special;
        List<Character> chars = new ArrayList<>();
        chars.add(upper.charAt(RANDOM.nextInt(upper.length())));
        chars.add(lower.charAt(RANDOM.nextInt(lower.length())));
        chars.add(digits.charAt(RANDOM.nextInt(digits.length())));
        chars.add(special.charAt(RANDOM.nextInt(special.length())));
        while (chars.size() < 12) {
            chars.add(all.charAt(RANDOM.nextInt(all.length())));
        }
        Collections.shuffle(chars, RANDOM);
        StringBuilder sb = new StringBuilder();
        chars.forEach(sb::append);
        return sb.toString();
    }
}
