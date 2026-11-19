package com.example.backend.admin.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.nearmiss.kb.NearMissKbBackfill;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사내 아차사고 RAG 일괄 적재 (회사 관리자, /api/v1/admin/** 는 ADMIN 만).
 * POST /api/v1/admin/near-misses/kb-backfill?siteId= (현장을 빼면 모든 현장). 적재가 꺼져 있으면 409
 */
@RestController
@RequestMapping("/api/v1/admin/near-misses")
public class AdminNearMissKbController {

    private final NearMissKbBackfill backfill;

    public AdminNearMissKbController(NearMissKbBackfill backfill) {
        this.backfill = backfill;
    }

    @PostMapping("/kb-backfill")
    public ApiResponse<NearMissKbBackfill.Result> backfill(@RequestParam(required = false) Long siteId) {
        return ApiResponse.ok(backfill.run(siteId));
    }
}
