package com.example.backend.stt.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.stt.dto.SttResponse;
import com.example.backend.stt.dto.SttStatusResponse;
import com.example.backend.stt.service.SttService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 음성 입력 (로그인 필요). 저장하지 않는다.
 *  - GET  /api/v1/stt/status   켜짐 여부·최대 녹음 초·최대 크기
 *  - POST /api/v1/stt          multipart: file(녹음), language(기본 ko) → {text, model}
 */
@RestController
@RequestMapping("/api/v1/stt")
public class SttController {

    private final SttService service;

    public SttController(SttService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public ApiResponse<SttStatusResponse> status() {
        return ApiResponse.ok(service.status());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SttResponse> transcribe(@RequestParam(value = "file", required = false) MultipartFile file,
                                               @RequestParam(value = "language", required = false) String language) {
        return ApiResponse.ok(service.transcribe(file, language));
    }
}
