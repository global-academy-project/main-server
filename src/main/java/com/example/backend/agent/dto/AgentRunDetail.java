package com.example.backend.agent.dto;

import com.example.backend.agent.entity.AgentRunStatus;
import com.example.backend.agent.entity.AgentStepStatus;
import com.example.backend.agent.entity.AgentStepType;
import com.example.backend.agent.entity.AgentTaskType;
import com.example.backend.agent.entity.EvidenceSourceType;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GET /api/v1/agent/runs/{runId}: 실행 상태와 결과.
 *  - result: AI 서버 응답(WorkRegisterResult) 그대로. 처리 중이거나 실패면 null
 *    (화면은 result.ask, result.approvalGate, result.verification, result.drafts 를 쓴다)
 *  - steps / citations: 저장된 단계·근거 (진행 표시, 근거 인용)
 *  - siteId: 실행 현장, permitId: 실행 요청 때 연결한 허가서(없으면 null),
 *    workId: 초안 저장으로 만들어지거나 고쳐진 작업(DONE 후, 없으면 null). 저장된 기록 전체는 result.saved
 */
public record AgentRunDetail(
        Long runId, Long conversationId, Long siteId, Long permitId, Long workId, AgentTaskType taskType, AgentRunStatus status,
        JsonNode result, String errorMessage, Integer totalMs,
        List<Step> steps, List<Citation> citations,
        LocalDateTime createdAt, LocalDateTime finishedAt
) {
    public record Step(int seq, AgentStepType type, String toolName, AgentStepStatus status, Integer latencyMs) {
    }

    public record Citation(EvidenceSourceType sourceType, String title, String locator, String url, String snippet) {
    }
}
