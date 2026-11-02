package com.example.backend.admin.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.site.dto.AdminSiteRequest;
import com.example.backend.site.dto.SiteSettingsResponse;
import com.example.backend.site.service.SiteSettingsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 현장 등록·이름·모드·사용 중지 (회사 관리자, /api/v1/admin/** 는 ADMIN 만) */
@RestController
@RequestMapping("/api/v1/admin/sites")
public class AdminSiteController {

    private final SiteSettingsService service;

    public AdminSiteController(SiteSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<SiteSettingsResponse>> list() {
        return ApiResponse.ok(service.listAll());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SiteSettingsResponse> create(@AuthenticationPrincipal Long adminId,
                                                    @Valid @RequestBody AdminSiteRequest request) {
        return ApiResponse.ok(service.create(adminId, request));
    }

    @PutMapping("/{siteId}")
    public ApiResponse<SiteSettingsResponse> update(@AuthenticationPrincipal Long adminId, @PathVariable Long siteId,
                                                    @Valid @RequestBody AdminSiteRequest request) {
        return ApiResponse.ok(service.updateByAdmin(adminId, siteId, request));
    }
}
