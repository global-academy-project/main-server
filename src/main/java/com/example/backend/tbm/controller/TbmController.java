package com.example.backend.tbm.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.tbm.dto.CompleteTbmRequest;
import com.example.backend.tbm.dto.SaveTbmAttendeesRequest;
import com.example.backend.tbm.dto.SaveTbmRequest;
import com.example.backend.tbm.dto.TbmResponse;
import com.example.backend.tbm.service.TbmCommandService;
import com.example.backend.tbm.service.TbmService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** TBM 조회·작성·수정·실시 완료·되돌리기·확인 요청 */
@RestController
@RequestMapping("/api/v1")
public class TbmController {

    private final TbmService tbmService;
    private final TbmCommandService commandService;

    public TbmController(TbmService tbmService, TbmCommandService commandService) {
        this.tbmService = tbmService;
        this.commandService = commandService;
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

    @PostMapping("/sites/{siteId}/tbm")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TbmResponse> create(@AuthenticationPrincipal Long userId, @PathVariable Long siteId,
                                           @Valid @RequestBody SaveTbmRequest request) {
        return ApiResponse.ok(commandService.create(userId, siteId, request));
    }

    /** 수정 (lockVersion 필수, 내용·작업·참석자 통째로) */
    @PutMapping("/tbm/{tbmId}")
    public ApiResponse<TbmResponse> update(@AuthenticationPrincipal Long userId, @PathVariable Long tbmId,
                                           @Valid @RequestBody SaveTbmRequest request) {
        return ApiResponse.ok(commandService.update(userId, tbmId, request));
    }

    /** 참석자만 통째로 바꾸기 (lockVersion 필수, 그 현장에 배치된 작업자만) */
    @PutMapping("/tbm/{tbmId}/attendees")
    public ApiResponse<TbmResponse> attendees(@AuthenticationPrincipal Long userId, @PathVariable Long tbmId,
                                              @Valid @RequestBody SaveTbmAttendeesRequest request) {
        return ApiResponse.ok(commandService.saveAttendees(userId, tbmId, request));
    }

    @PostMapping("/tbm/{tbmId}/complete")
    public ApiResponse<TbmResponse> complete(@AuthenticationPrincipal Long userId, @PathVariable Long tbmId,
                                             @Valid @RequestBody CompleteTbmRequest request) {
        return ApiResponse.ok(commandService.complete(userId, tbmId, request));
    }

    @PostMapping("/tbm/{tbmId}/reopen")
    public ApiResponse<TbmResponse> reopen(@AuthenticationPrincipal Long userId, @PathVariable Long tbmId) {
        return ApiResponse.ok(commandService.reopen(userId, tbmId));
    }

    /** 확인 요청 (실시 완료 후) → 승인함 */
    @PostMapping("/tbm/{tbmId}/submit")
    public ApiResponse<TbmResponse> submit(@AuthenticationPrincipal Long userId, @PathVariable Long tbmId) {
        return ApiResponse.ok(commandService.submit(userId, tbmId));
    }
}
