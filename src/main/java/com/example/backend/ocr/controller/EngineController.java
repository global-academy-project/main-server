package com.example.backend.ocr.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.ocr.client.EngineInfo;
import com.example.backend.ocr.service.EngineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/engines")
public class EngineController {

    private final EngineService engineService;

    public EngineController(EngineService engineService) {
        this.engineService = engineService;
    }

    /** 4. 엔진/망 모드 */
    @GetMapping
    public ApiResponse<EngineInfo> engines() {
        return ApiResponse.ok(engineService.getEngines());
    }
}
