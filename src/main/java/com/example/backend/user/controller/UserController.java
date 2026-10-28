package com.example.backend.user.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.user.dto.EmailChangeRequest;
import com.example.backend.user.dto.MeResponse;
import com.example.backend.user.dto.SelectSiteRequest;
import com.example.backend.user.dto.UpdateMeRequest;
import com.example.backend.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 내 정보. 비밀번호 변경은 POST /api/v1/auth/password/change (세션 쿠키 경로가 /api/v1/auth 라서 그쪽에 있다).
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(userService.getMe(userId));
    }

    @PatchMapping("/me")
    public ApiResponse<MeResponse> updateMe(@AuthenticationPrincipal Long userId,
                                            @Valid @RequestBody UpdateMeRequest request) {
        return ApiResponse.ok(userService.updateMe(userId, request));
    }

    @PutMapping("/me/last-site")
    public ApiResponse<Object> selectSite(@AuthenticationPrincipal Long userId,
                                          @Valid @RequestBody SelectSiteRequest request) {
        userService.selectSite(userId, request.siteId());
        return ApiResponse.okEmpty();
    }

    @PostMapping("/me/email-change")
    public ApiResponse<Object> requestEmailChange(@AuthenticationPrincipal Long userId,
                                                  @Valid @RequestBody EmailChangeRequest request,
                                                  HttpServletRequest http) {
        userService.requestEmailChange(userId, request, http.getRemoteAddr());
        return ApiResponse.okEmpty();
    }
}
