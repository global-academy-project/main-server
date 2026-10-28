package com.example.backend.work.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.work.dto.CreateWorkRequest;
import com.example.backend.work.dto.WorkResponse;
import com.example.backend.work.service.WorkService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/works")
public class WorkController {

    private final WorkService workService;

    public WorkController(WorkService workService) {
        this.workService = workService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkResponse> create(@AuthenticationPrincipal Long userId,
                                            @Valid @RequestBody CreateWorkRequest request) {
        return ApiResponse.ok(workService.create(userId, request));
    }

    @GetMapping("/{workId}")
    public ApiResponse<WorkResponse> get(@AuthenticationPrincipal Long userId, @PathVariable Long workId) {
        return ApiResponse.ok(workService.get(userId, workId));
    }
}
