package com.example.backend.admin.controller;

import com.example.backend.admin.dto.AdminUserResponse;
import com.example.backend.admin.dto.ApproveRequest;
import com.example.backend.admin.dto.RoleRequest;
import com.example.backend.admin.dto.SiteGrantsRequest;
import com.example.backend.admin.dto.TemporaryPasswordResponse;
import com.example.backend.admin.service.AdminUserService;
import com.example.backend.common.response.ApiResponse;
import com.example.backend.user.entity.UserStatus;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 관리자 메뉴 > 회원 관리. ADMIN 만 호출 가능 (SecurityConfig). 일반 회원이 부르면 403 FORBIDDEN.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    /** 회원 목록. ?status=PENDING 이면 승인 대기만 */
    @GetMapping
    public ApiResponse<List<AdminUserResponse>> list(@RequestParam(required = false) UserStatus status) {
        return ApiResponse.ok(service.list(status));
    }

    @GetMapping("/{userId}")
    public ApiResponse<AdminUserResponse> get(@PathVariable Long userId) {
        return ApiResponse.ok(service.get(userId));
    }

    /** 가입 승인: {"role":"MEMBER","sites":[{"siteId":1,"role":"WRITER"}]} */
    @PostMapping("/{userId}/approve")
    public ApiResponse<AdminUserResponse> approve(@AuthenticationPrincipal Long adminId, @PathVariable Long userId,
                                                  @Valid @RequestBody ApproveRequest request) {
        return ApiResponse.ok(service.approve(adminId, userId, request));
    }

    /** 현장 권한 전체 교체: {"sites":[...]} (빠진 현장은 권한 회수) */
    @PutMapping("/{userId}/sites")
    public ApiResponse<AdminUserResponse> replaceSites(@AuthenticationPrincipal Long adminId, @PathVariable Long userId,
                                                       @Valid @RequestBody SiteGrantsRequest request) {
        return ApiResponse.ok(service.replaceSites(adminId, userId, request.sites()));
    }

    @PutMapping("/{userId}/role")
    public ApiResponse<AdminUserResponse> changeRole(@AuthenticationPrincipal Long adminId, @PathVariable Long userId,
                                                     @Valid @RequestBody RoleRequest request) {
        return ApiResponse.ok(service.changeRole(adminId, userId, request.role()));
    }

    @PostMapping("/{userId}/unlock")
    public ApiResponse<AdminUserResponse> unlock(@PathVariable Long userId) {
        return ApiResponse.ok(service.unlock(userId));
    }

    @PostMapping("/{userId}/temporary-password")
    public ApiResponse<TemporaryPasswordResponse> temporaryPassword(@PathVariable Long userId) {
        return ApiResponse.ok(service.issueTemporaryPassword(userId));
    }

    @PostMapping("/{userId}/verify-email")
    public ApiResponse<AdminUserResponse> verifyEmail(@PathVariable Long userId) {
        return ApiResponse.ok(service.verifyEmailInPerson(userId));
    }

    @PostMapping("/{userId}/deactivate")
    public ApiResponse<AdminUserResponse> deactivate(@AuthenticationPrincipal Long adminId, @PathVariable Long userId) {
        return ApiResponse.ok(service.deactivate(adminId, userId));
    }

    @PostMapping("/{userId}/reactivate")
    public ApiResponse<AdminUserResponse> reactivate(@PathVariable Long userId) {
        return ApiResponse.ok(service.reactivate(userId));
    }
}
