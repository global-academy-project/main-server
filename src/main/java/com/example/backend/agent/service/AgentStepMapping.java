package com.example.backend.agent.service;

import com.example.backend.agent.entity.AgentStep;
import com.example.backend.agent.entity.AgentStepStatus;
import com.example.backend.agent.entity.AgentStepType;

import java.util.Map;

/**
 * AI 서버 단계(steps[]) → agent_steps 종류 (B 답신 5장, C 회신 1-5).
 *   TOOL + 도구 이름        → TOOL_CALL
 *   evidence.plan           → PLAN
 *   evidence.sufficiency    → SUFFICIENCY
 *   evidence.search         → 저장 안 함 (실제 검색은 TOOL 단계로 남음)
 *   judgment / verification → JUDGE / VERIFY
 *   OK / ERROR              → DONE / FAILED (그 밖의 값은 끝났다고 볼 수 없으므로 FAILED)
 * 모르는 이름은 저장하지 않는다 (null). 응답 전체는 output_json 에 남는다.
 */
final class AgentStepMapping {

    private static final Map<String, AgentStepType> PHASES = Map.of(
            "evidence.plan", AgentStepType.PLAN,
            "evidence.sufficiency", AgentStepType.SUFFICIENCY,
            "judgment", AgentStepType.JUDGE,
            "verification", AgentStepType.VERIFY);

    private AgentStepMapping() {
    }

    /** 저장할 종류. 저장하지 않을 단계면 null */
    static AgentStepType typeOf(String kind, String name, String toolName) {
        if ("TOOL".equals(kind)) {
            return toolName != null && AgentStep.TOOL_NAMES.contains(toolName) ? AgentStepType.TOOL_CALL : null;
        }
        if ("PHASE".equals(kind)) {
            return PHASES.get(name);
        }
        return null;
    }

    /** OK 만 DONE. ERROR·빈 값·모르는 값은 FAILED (모르는 상태를 성공으로 기록하지 않는다) */
    static AgentStepStatus statusOf(String status) {
        return "OK".equals(status) ? AgentStepStatus.DONE : AgentStepStatus.FAILED;
    }
}
