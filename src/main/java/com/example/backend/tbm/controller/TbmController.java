package com.example.backend.tbm.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.tbm.dto.TbmResponse;
import com.example.backend.tbm.service.TbmService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** TBM 조회 (작성·실시 완료·확인은 다음) */
@RestController
@RequestMapping("/api/v1")
public class TbmController {

    private final TbmService tbmService;

    public TbmController(TbmService tbmService) {
        this.tbmService = tbmService;
    }

    @GetMapping("/tbm/{tbmId}")
    public ApiResponse<TbmResponse> get(@AuthenticationPrincipal Long userId, @PathVariable Long tbmId) {
        return ApiResponse.ok(tbmService.get(userId, tbmId));
    }

    /** 작업에 연결된 가장 최근 TBM (에이전트 초안 확인) */
    @GetMapping("/works/{workId}/tbm")
    public ApiResponse<TbmResponse> getByWork(@AuthenticationPrincipal Long userId, @PathVariable Long workId) {
        return ApiResponse.ok(tbmService.getByWork(userId, workId));
    }

    /** 현장의 그날 TBM: ?date=2026-11-02 (비우면 오늘) */
    @GetMapping("/sites/{siteId}/tbm")
    public ApiResponse<List<TbmResponse>> list(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(tbmService.listByDate(userId, siteId, date));
    }
}
