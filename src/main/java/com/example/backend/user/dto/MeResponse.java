package com.example.backend.user.dto;

import com.example.backend.site.service.SiteAccess;

import java.util.List;

/**
 * GET /api/v1/users/me
 * - user: 기본 정보 (로그인 응답의 user 와 같은 모양)
 * - lockVersion: 내 정보 수정(PATCH) 때 그대로 돌려보낸다 (다른 곳에서 먼저 고쳤으면 409)
 * - currentSiteId: 드롭다운 기본값. 마지막으로 고른 현장, 없거나 권한이 사라졌으면 목록의 첫 현장, 현장이 없으면 null
 * - sites: 내가 볼 수 있는 현장 (드롭다운 목록)
 */
public record MeResponse(UserResponse user, int lockVersion, Long currentSiteId, List<SiteAccess> sites) {
}
