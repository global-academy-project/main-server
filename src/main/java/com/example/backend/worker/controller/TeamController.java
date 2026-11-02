package com.example.backend.worker.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.worker.dto.TeamRequest;
import com.example.backend.worker.dto.TeamResponse;
import com.example.backend.worker.service.TeamService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 팀·협력업체. 조회는 로그인한 사람, 등록·수정은 회사 관리자 (/api/v1/admin/** 는 ADMIN 만, SecurityConfig) */
@RestController
@RequestMapping("/api/v1")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @GetMapping("/teams")
    public ApiResponse<List<TeamResponse>> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.ok(teamService.list(includeInactive));
    }

    @PostMapping("/admin/teams")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TeamResponse> create(@AuthenticationPrincipal Long adminId, @Valid @RequestBody TeamRequest request) {
        return ApiResponse.ok(teamService.create(adminId, request));
    }

    @PutMapping("/admin/teams/{teamId}")
    public ApiResponse<TeamResponse> update(@AuthenticationPrincipal Long adminId, @PathVariable Long teamId,
                                            @Valid @RequestBody TeamRequest request) {
        return ApiResponse.ok(teamService.update(adminId, teamId, request));
    }
}
