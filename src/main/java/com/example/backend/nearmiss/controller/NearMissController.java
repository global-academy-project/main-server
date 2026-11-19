package com.example.backend.nearmiss.controller;

import com.example.backend.agent.dto.AgentRunStarted;
import com.example.backend.common.response.ApiResponse;
import com.example.backend.nearmiss.dto.AutoTagRequest;
import com.example.backend.nearmiss.dto.IncidentSearchRequest;
import com.example.backend.nearmiss.dto.NearMissResponse;
import com.example.backend.nearmiss.dto.ReportNearMissRequest;
import com.example.backend.nearmiss.dto.SaveTagsRequest;
import com.example.backend.nearmiss.dto.TagCodeResponse;
import com.example.backend.nearmiss.service.NearMissAutoTagService;
import com.example.backend.nearmiss.service.NearMissService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 아차사고 보고·조회·태그 검토·자동 태깅, 태그 코드표, 관련 사고 검색 */
@RestController
@RequestMapping("/api/v1")
public class NearMissController {

    private final NearMissService service;
    private final NearMissAutoTagService autoTagService;

    public NearMissController(NearMissService service, NearMissAutoTagService autoTagService) {
        this.service = service;
        this.autoTagService = autoTagService;
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

    /**
     * 자동 태깅 시작 (시나리오 C). 보고자 본인 또는 승인자 이상, 사람이 아직 태그를 저장하지 않은 보고만.
     * 202 {runId}. 결과는 GET /agent/runs/{runId} 로 보고, 끝나면 보고를 다시 읽는다 (AI 태그는 제안, 저장해야 확정)
     */
    @PostMapping("/near-misses/{nearMissId}/auto-tags")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AgentRunStarted> autoTags(@AuthenticationPrincipal Long userId, @PathVariable Long nearMissId,
                                                 @Valid @RequestBody AutoTagRequest request) {
        return ApiResponse.ok(autoTagService.start(userId, nearMissId, request.lockVersion()));
    }

    @PostMapping("/incident-search")
    public ApiResponse<List<NearMissResponse>> search(@AuthenticationPrincipal Long userId,
                                                      @Valid @RequestBody IncidentSearchRequest request) {
        return ApiResponse.ok(service.search(userId, request));
    }
}
