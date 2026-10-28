package com.example.backend.admin.dto;

import com.example.backend.user.entity.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * POST /api/v1/admin/users/{id}/approve
 * role: MEMBER(현장 권한이 있는 곳만) | ADMIN(모든 현장). 비우면 MEMBER
 * sites: MEMBER 는 1개 이상 필요 (권한 현장이 없으면 로그인해도 볼 게 없다)
 */
public record ApproveRequest(
        UserRole role,
        @NotNull
        List<@Valid SiteGrant> sites
) {
    public UserRole roleOrDefault() {
        return role == null ? UserRole.MEMBER : role;
    }
}
