package com.example.backend.nearmiss.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.nearmiss.dto.IncidentSearchRequest;
import com.example.backend.nearmiss.dto.NearMissResponse;
import com.example.backend.nearmiss.dto.ReportNearMissRequest;
import com.example.backend.nearmiss.dto.SaveTagsRequest;
import com.example.backend.nearmiss.dto.TagCodeResponse;
import com.example.backend.nearmiss.service.NearMissService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 아차사고 보고·조회·태그 검토, 태그 코드표, 관련 사고 검색 */
@RestController
@RequestMapping("/api/v1")
public class NearMissController {

    private final NearMissService service;

    public NearMissController(NearMissService service) {
        this.service = service;
    }

    @GetMapping("/tag-codes")
    public ApiResponse<List<TagCodeResponse>> tagCodes() {
        return ApiResponse.ok(service.tagCodes());
    }

    @PostMapping("/near-misses")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<NearMissResponse> report(@AuthenticationPrincipal Long userId,
                                                @Valid @RequestBody ReportNearMissRequest request) {
        return ApiResponse.ok(service.report(userId, request));
    }

    @GetMapping("/near-misses/{nearMissId}")
    public ApiResponse<NearMissResponse> get(@AuthenticationPrincipal Long userId, @PathVariable Long nearMissId) {
        return ApiResponse.ok(service.get(userId, nearMissId));
    }

    @GetMapping("/sites/{siteId}/near-misses")
    public ApiResponse<List<NearMissResponse>> list(@AuthenticationPrincipal Long userId, @PathVariable Long siteId) {
        return ApiResponse.ok(service.list(userId, siteId));
    }

    @PutMapping("/near-misses/{nearMissId}/tags")
    public ApiResponse<NearMissResponse> saveTags(@AuthenticationPrincipal Long userId, @PathVariable Long nearMissId,
                                                  @Valid @RequestBody SaveTagsRequest request) {
        return ApiResponse.ok(service.saveTags(userId, nearMissId, request));
    }

    @PostMapping("/incident-search")
    public ApiResponse<List<NearMissResponse>> search(@AuthenticationPrincipal Long userId,
                                                      @Valid @RequestBody IncidentSearchRequest request) {
        return ApiResponse.ok(service.search(userId, request));
    }
}
