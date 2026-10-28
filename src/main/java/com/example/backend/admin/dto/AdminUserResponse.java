package com.example.backend.admin.dto;

import com.example.backend.site.service.SiteAccess;
import com.example.backend.user.entity.EmailVerifiedVia;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserRole;
import com.example.backend.user.entity.UserStatus;

import java.time.LocalDateTime;
import java.util.List;

/** 관리자 화면의 회원 한 줄. sites 는 ACTIVE 권한만 (role = WRITER | APPROVER | SITE_ADMIN) */
public record AdminUserResponse(
        Long userId,
        String email,
        String name,
        UserRole role,
        UserStatus status,
        String phone,
        String department,
        String jobTitle,
        boolean emailVerified,
        EmailVerifiedVia emailVerifiedVia,
        int failedLoginCount,
        boolean mustChangePassword,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt,
        List<SiteAccess> sites
) {
    public static AdminUserResponse of(User u, List<SiteAccess> sites) {
        return new AdminUserResponse(u.getId(), u.getEmail(), u.getName(), u.getRole(), u.getStatus(),
                u.getPhone(), u.getDepartment(), u.getJobTitle(), u.isEmailVerified(), u.getEmailVerifiedVia(),
                u.getFailedLoginCount(), u.isMustChangePassword(), u.getCreatedAt(), u.getLastLoginAt(), sites);
    }
}
