package com.example.backend.extract.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.extract.dto.ExtractRequest;
import com.example.backend.extract.dto.ExtractResponse;
import com.example.backend.extract.service.RuleInputExtractService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 문장 → 규칙 입력 추출 (허가 화면 "문장으로 입력"). 저장하지 않는 미리 확인 */
@RestController
@RequestMapping("/api/v1/extract")
public class ExtractController {

    private final RuleInputExtractService service;

    public ExtractController(RuleInputExtractService service) {
        this.service = service;
    }

    @PostMapping("/rule-input")
    public ApiResponse<ExtractResponse> extract(@AuthenticationPrincipal Long userId, @Valid @RequestBody ExtractRequest request) {
        return ApiResponse.ok(service.extract(userId, request));
    }
}
