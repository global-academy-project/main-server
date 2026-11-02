package com.example.backend.site.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.site.dto.SiteSettingsRequest;
import com.example.backend.site.dto.SiteSettingsResponse;
import com.example.backend.site.service.SiteSettingsService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 현장 운영 설정. 조회는 현장 권한, 변경은 현장 관리자 이상 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/settings")
public class SiteSettingsController {

    private final SiteSettingsService service;

    public SiteSettingsController(SiteSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<SiteSettingsResponse> get(@AuthenticationPrincipal Long userId, @PathVariable Long siteId) {
        return ApiResponse.ok(service.get(userId, siteId));
    }

    @PutMapping
    public ApiResponse<SiteSettingsResponse> update(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                                    @Valid @RequestBody SiteSettingsRequest request) {
        return ApiResponse.ok(service.update(userId, siteId, request));
    }
}
