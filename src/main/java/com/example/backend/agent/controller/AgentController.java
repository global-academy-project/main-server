package com.example.backend.agent.controller;

import com.example.backend.agent.dto.AgentMessageItem;
import com.example.backend.agent.dto.AgentRunDetail;
import com.example.backend.agent.dto.AgentRunStarted;
import com.example.backend.agent.dto.StartRecordQueryRequest;
import com.example.backend.agent.dto.StartWorkRegisterRequest;
import com.example.backend.agent.service.AgentRunService;
import com.example.backend.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 에이전트 (작업 등록 대화, 기록 질문). 로그인 + 그 현장 권한 필요.
 * 메시지를 보내면 바로 202 로 runId 를 돌려주고, AI 처리는 뒤에서 한다 → GET /runs/{runId} 로 상태 조회.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final AgentRunService agentRunService;

    public AgentController(AgentRunService agentRunService) {
        this.agentRunService = agentRunService;
    }

    @PostMapping("/work-register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AgentRunStarted> workRegister(@AuthenticationPrincipal Long userId,
                                                     @Valid @RequestBody StartWorkRegisterRequest request) {
        return ApiResponse.ok(agentRunService.startWorkRegister(userId, request));
    }

    /** 기록 질문 (기록 질문 탭). 업무 기록은 읽기만 한다. 결과는 GET /runs/{runId} 의 result (answer·queries·evidence) */
    @PostMapping("/record-query")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AgentRunStarted> recordQuery(@AuthenticationPrincipal Long userId,
                                                    @Valid @RequestBody StartRecordQueryRequest request) {
        return ApiResponse.ok(agentRunService.startRecordQuery(userId, request));
    }

    /** 실행 상태: PROCESSING(처리 중) → WAITING_INPUT(되묻기) / DONE(완료) / FAILED */
    @GetMapping("/runs/{runId}")
    public ApiResponse<AgentRunDetail> run(@AuthenticationPrincipal Long userId, @PathVariable Long runId) {
        return ApiResponse.ok(agentRunService.getRun(userId, runId));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ApiResponse<List<AgentMessageItem>> messages(@AuthenticationPrincipal Long userId,
                                                        @PathVariable Long conversationId) {
        return ApiResponse.ok(agentRunService.getMessages(userId, conversationId));
    }
}
