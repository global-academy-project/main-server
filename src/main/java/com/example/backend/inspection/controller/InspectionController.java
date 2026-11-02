package com.example.backend.inspection.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.inspection.dto.CreateInspectionRequest;
import com.example.backend.inspection.dto.InspectionResponse;
import com.example.backend.inspection.dto.SaveInspectionRequest;
import com.example.backend.inspection.service.InspectionService;
import com.example.backend.inspection.service.InspectionTemplates;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** 장비·구역 점검표: 양식 목록, 만들기, 조회, 결과 저장, 제출(승인 요청). 승인·반려·회수는 결재 API */
@RestController
@RequestMapping("/api/v1")
public class InspectionController {

    private final InspectionService service;

    public InspectionController(InspectionService service) {
        this.service = service;
    }

    @GetMapping("/inspection-templates")
    public ApiResponse<List<InspectionTemplates.Template>> templates() {
        return ApiResponse.ok(service.templates());
    }

    @PostMapping("/sites/{siteId}/inspections")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InspectionResponse> create(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                                  @Valid @RequestBody CreateInspectionRequest request) {
        return ApiResponse.ok(service.create(userId, siteId, request));
    }

    @GetMapping("/sites/{siteId}/inspections")
    public ApiResponse<List<InspectionResponse>> list(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.list(userId, siteId, from, to));
    }

    @GetMapping("/inspections/{inspectionId}")
    public ApiResponse<InspectionResponse> get(@AuthenticationPrincipal Long userId, @PathVariable Long inspectionId) {
        return ApiResponse.ok(service.get(userId, inspectionId));
    }

    @PutMapping("/inspections/{inspectionId}")
    public ApiResponse<InspectionResponse> save(@AuthenticationPrincipal Long userId, @PathVariable Long inspectionId,
                                                @Valid @RequestBody SaveInspectionRequest request) {
        return ApiResponse.ok(service.save(userId, inspectionId, request));
    }

    @PostMapping("/inspections/{inspectionId}/submit")
    public ApiResponse<InspectionResponse> submit(@AuthenticationPrincipal Long userId, @PathVariable Long inspectionId) {
        return ApiResponse.ok(service.submit(userId, inspectionId));
    }
}
