package com.example.backend.worker.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.worker.dto.SiteWorkerResponse;
import com.example.backend.worker.service.SiteWorkerService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 현장 작업자 (TBM 참석자 선택). 작업자 등록·배치 API 는 기준 정보 작업에서 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/workers")
public class SiteWorkerController {

    private final SiteWorkerService siteWorkerService;

    public SiteWorkerController(SiteWorkerService siteWorkerService) {
        this.siteWorkerService = siteWorkerService;
    }

    @GetMapping
    public ApiResponse<List<SiteWorkerResponse>> list(@AuthenticationPrincipal Long userId, @PathVariable Long siteId) {
        return ApiResponse.ok(siteWorkerService.list(userId, siteId));
    }
}
