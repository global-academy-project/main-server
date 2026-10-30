package com.example.backend.record.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.record.dto.SearchRecordsRequest;
import com.example.backend.record.dto.SearchRecordsResult;
import com.example.backend.record.service.RecordSearchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 에이전트 도구 search_records (AI 서버 → Spring, 내부 토큰). 웹은 이 경로를 직접 부르지 않고
 * 기록 질문 실행(POST /api/v1/agent/record-query)을 거친다. siteId·agentRunId 는 AI 서버 코드가 채운다.
 */
@RestController
@RequestMapping("/internal/v1/tools")
public class InternalRecordToolController {

    private final RecordSearchService recordSearchService;

    public InternalRecordToolController(RecordSearchService recordSearchService) {
        this.recordSearchService = recordSearchService;
    }

    @PostMapping("/search_records")
    public ApiResponse<SearchRecordsResult> searchRecords(@Valid @RequestBody SearchRecordsRequest request) {
        return ApiResponse.ok(recordSearchService.search(request));
    }
}
