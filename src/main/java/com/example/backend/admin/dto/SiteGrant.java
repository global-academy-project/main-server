package com.example.backend.admin.dto;

import com.example.backend.site.entity.SiteRole;
import jakarta.validation.constraints.NotNull;

/** 현장 하나에 주는 권한 */
public record SiteGrant(
        @NotNull Long siteId,
        @NotNull SiteRole role
) {
}
