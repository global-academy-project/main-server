package com.example.backend.site.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.site.dto.ZoneRequest;
import com.example.backend.site.dto.ZoneResponse;
import com.example.backend.site.service.ZoneService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 현장 작업구역 (작업·아차사고·점검의 구역 선택). 등록·수정은 현장 관리자 이상 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/zones")
public class ZoneController {

    private final ZoneService zoneService;

    public ZoneController(ZoneService zoneService) {
        this.zoneService = zoneService;
    }

    @GetMapping
    public ApiResponse<List<ZoneResponse>> list(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                                @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.ok(zoneService.list(userId, siteId, includeInactive));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ZoneResponse> create(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                            @Valid @RequestBody ZoneRequest request) {
        return ApiResponse.ok(zoneService.create(userId, siteId, request));
    }

    @PutMapping("/{zoneId}")
    public ApiResponse<ZoneResponse> update(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                            @PathVariable Long zoneId, @Valid @RequestBody ZoneRequest request) {
        return ApiResponse.ok(zoneService.update(userId, siteId, zoneId, request));
    }
}
