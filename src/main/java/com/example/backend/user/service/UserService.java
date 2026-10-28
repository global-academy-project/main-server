package com.example.backend.user.service;

import com.example.backend.auth.entity.TokenPurpose;
import com.example.backend.auth.mail.AuthMailSender;
import com.example.backend.auth.mail.WebLinks;
import com.example.backend.auth.service.AccountTokenService;
import com.example.backend.auth.service.PasswordService;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.util.AfterCommit;
import com.example.backend.site.service.SiteAccess;
import com.example.backend.site.service.SiteAccessService;
import com.example.backend.user.dto.EmailChangeRequest;
import com.example.backend.user.dto.MeResponse;
import com.example.backend.user.dto.UpdateMeRequest;
import com.example.backend.user.dto.UserResponse;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/** 내 정보: 조회·수정, 현장 선택, 이메일 변경 요청. 비밀번호 변경은 AuthService (세션 쿠키를 다시 줘야 해서). */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final SiteAccessService siteAccessService;
    private final PasswordService passwordService;
    private final AccountTokenService tokenService;
    private final AuthMailSender mailSender;
    private final WebLinks webLinks;

    public UserService(UserRepository userRepository, SiteAccessService siteAccessService,
                       PasswordService passwordService, AccountTokenService tokenService, AuthMailSender mailSender,
                       WebLinks webLinks) {
        this.userRepository = userRepository;
        this.siteAccessService = siteAccessService;
        this.passwordService = passwordService;
        this.tokenService = tokenService;
        this.mailSender = mailSender;
        this.webLinks = webLinks;
    }

    @Transactional(readOnly = true)
    public MeResponse getMe(Long userId) {
        return toMe(load(userId));
    }

    /** 이름·전화·부서·직책 수정. 화면에서 받은 lockVersion 이 지금 값과 다르면 다른 곳에서 먼저 고친 것 → 409 */
    @Transactional
    public MeResponse updateMe(Long userId, UpdateMeRequest request) {
        User user = load(userId);
        if (user.getLockVersion() != request.lockVersion()) {
            throw new BusinessException(ErrorCode.CONFLICT_MODIFIED);
        }
        user.updateMyInfo(request.name(), request.phone(), request.department(), request.jobTitle());
        userRepository.saveAndFlush(user);   // lockVersion 을 올린 값으로 응답하려고 바로 반영
        return toMe(user);
    }

    /** 드롭다운에서 고른 현장 저장. 권한 없는 현장이면 SITE_NOT_ALLOWED */
    @Transactional
    public void selectSite(Long userId, Long siteId) {
        User user = load(userId);
        if (!siteAccessService.canAccess(user, siteId)) {
            throw new BusinessException(ErrorCode.SITE_NOT_ALLOWED);
        }
        user.selectSite(siteId);
    }

    /**
     * 이메일 변경 요청: 현재 비밀번호 확인 → 새 주소로 확인 메일.
     * 링크를 누르면(POST /api/v1/auth/email/change/confirm) 바뀐다. 그 전까지는 지금 이메일 그대로.
     */
    @Transactional
    public void requestEmailChange(Long userId, EmailChangeRequest request, String clientIp) {
        User user = load(userId);
        passwordService.checkCurrent(user, request.currentPassword());
        String newEmail = request.newEmail().trim().toLowerCase(Locale.ROOT);
        if (newEmail.equals(user.getEmail())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지금 쓰는 이메일과 같습니다.");
        }
        if (userRepository.existsByEmail(newEmail)) {
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATED);
        }
        AccountTokenService.Issued issued = tokenService.issue(user.getId(), TokenPurpose.EMAIL_CHANGE, newEmail, clientIp)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOO_MANY_REQUESTS));
        String link = webLinks.verifyEmailChange(issued.rawToken());
        String name = user.getName();
        AfterCommit.run(() -> mailSender.sendEmailChange(newEmail, name, link, issued.validMinutes()));
    }

    private User load(Long userId) {
        // 토큰은 유효하지만 사용자가 없는 경우는 인증 실패로 취급
        return userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    private MeResponse toMe(User user) {
        List<SiteAccess> sites = siteAccessService.accessibleSites(user);
        Long current = sites.stream().map(SiteAccess::siteId)
                .filter(id -> id.equals(user.getLastSiteId()))
                .findFirst()
                .orElse(sites.isEmpty() ? null : sites.get(0).siteId());
        return new MeResponse(UserResponse.from(user), user.getLockVersion(), current, sites);
    }
}
