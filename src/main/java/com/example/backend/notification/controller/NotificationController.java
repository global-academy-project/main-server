package com.example.backend.notification.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.notification.dto.NotificationItem;
import com.example.backend.notification.dto.NotificationList;
import com.example.backend.notification.dto.NotificationPreferenceItem;
import com.example.backend.notification.service.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 내 알림 (승인 요청·결과, 마감 알림 등). 화면은 목록·안 읽은 수를 주기적으로 조회한다 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** ?siteId=1 (그 현장 + 현장 없는 알림만), ?unreadOnly=true */
    @GetMapping
    public ApiResponse<NotificationList> list(@AuthenticationPrincipal Long userId,
                                              @RequestParam(required = false) Long siteId,
                                              @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return ApiResponse.ok(notificationService.list(userId, siteId, unreadOnly));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> unreadCount(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(Map.of("unreadCount", notificationService.unreadCount(userId)));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<NotificationItem> read(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        return ApiResponse.ok(notificationService.read(userId, id));
    }

    @PostMapping("/read-all")
    public ApiResponse<Map<String, Long>> readAll(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(Map.of("unreadCount", notificationService.readAll(userId)));
    }

    /** 끄기 (목록에서 사라짐, 읽음도 같이) */
    @PostMapping("/{id}/dismiss")
    public ApiResponse<Object> dismiss(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        notificationService.dismiss(userId, id);
        return ApiResponse.okEmpty();
    }

    @GetMapping("/preferences")
    public ApiResponse<List<NotificationPreferenceItem>> preferences(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(notificationService.preferences(userId));
    }

    /** 보낸 종류만 바꾼다. 안전 필수 알림(mandatory)의 웹 알림은 끌 수 없음 (400) */
    @PutMapping("/preferences")
    public ApiResponse<List<NotificationPreferenceItem>> updatePreferences(
            @AuthenticationPrincipal Long userId,
            @RequestBody @NotNull @Size(max = 20) List<@Valid @NotNull NotificationPreferenceItem> changes) {
        return ApiResponse.ok(notificationService.updatePreferences(userId, changes));
    }
}
