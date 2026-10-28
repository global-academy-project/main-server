package com.example.backend.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** PUT /api/v1/admin/users/{id}/sites: 이 목록으로 통째로 바꾼다 (없는 현장은 권한 회수) */
public record SiteGrantsRequest(
        @NotNull @Valid
        List<SiteGrant> sites
) {
}
