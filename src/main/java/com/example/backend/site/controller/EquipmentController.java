package com.example.backend.site.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.site.dto.EquipmentRequest;
import com.example.backend.site.dto.EquipmentResponse;
import com.example.backend.site.service.EquipmentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 현장 설비 (장비 점검표 대상). 등록·수정은 현장 관리자 이상 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/equipment")
public class EquipmentController {

    private final EquipmentService service;

    public EquipmentController(EquipmentService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<EquipmentResponse>> list(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                                     @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.ok(service.list(userId, siteId, includeInactive));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<EquipmentResponse> create(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                                 @Valid @RequestBody EquipmentRequest request) {
        return ApiResponse.ok(service.create(userId, siteId, request));
    }

    @PutMapping("/{equipmentId}")
    public ApiResponse<EquipmentResponse> update(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                                 @PathVariable Long equipmentId, @Valid @RequestBody EquipmentRequest request) {
        return ApiResponse.ok(service.update(userId, siteId, equipmentId, request));
    }
}
