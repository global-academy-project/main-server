package com.example.backend.site.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import org.springframework.stereotype.Component;

/**
 * 업무 API 공통: 로그인한 사용자가 그 현장 권한이 있는지 확인한다 (관리자는 모든 현장).
 * 작업·허가 등 현장 데이터를 다루는 서비스가 쓴다.
 */
@Component
public class SiteGuard {

    private final UserRepository userRepository;
    private final SiteAccessService siteAccessService;

    public SiteGuard(UserRepository userRepository, SiteAccessService siteAccessService) {
        this.userRepository = userRepository;
        this.siteAccessService = siteAccessService;
    }

    /** 권한이 있으면 사용자를 돌려준다. 로그인 정보가 없으면 UNAUTHORIZED, 현장 권한이 없으면 SITE_NOT_ALLOWED */
    public User requireAccess(Long userId, Long siteId) {
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (siteId == null || !siteAccessService.canAccess(user, siteId)) {
            throw new BusinessException(ErrorCode.SITE_NOT_ALLOWED);
        }
        return user;
    }

    /**
     * 현장 관리 권한(현장 관리자 이상: SITE_ADMIN, 회사 관리자)이 있으면 사용자를 돌려준다. 없으면 403 FORBIDDEN.
     * 구역·작업자 배치 같은 현장 기준 정보를 바꿀 때 쓴다.
     */
    public User requireSiteAdmin(Long userId, Long siteId) {
        User user = requireAccess(userId, siteId);
        String role = siteAccessService.roleAt(user, siteId).orElse(null);
        if (!"SITE_ADMIN".equals(role) && !"ADMIN".equals(role)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "현장 관리자만 바꿀 수 있습니다.");
        }
        return user;
    }
}
