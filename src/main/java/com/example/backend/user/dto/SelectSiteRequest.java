package com.example.backend.user.dto;

import jakarta.validation.constraints.NotNull;

/** PUT /api/v1/users/me/last-site. 드롭다운에서 고른 현장 (다음 로그인 때 기본값) */
public record SelectSiteRequest(
        @NotNull
        Long siteId
) {
}
