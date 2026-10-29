package com.example.backend.agent.service;

import com.example.backend.agent.entity.AgentStepStatus;
import com.example.backend.agent.entity.AgentStepType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AI 서버 단계 → agent_steps 종류")
class AgentStepMappingTest {

    @Test
    @DisplayName("TOOL 은 허용된 도구 이름만 TOOL_CALL, 모르는 도구는 저장 안 함")
    void tools() {
        assertThat(AgentStepMapping.typeOf("TOOL", "check_rules", "check_rules")).isEqualTo(AgentStepType.TOOL_CALL);
        assertThat(AgentStepMapping.typeOf("TOOL", "unknown_tool", "unknown_tool")).isNull();
        assertThat(AgentStepMapping.typeOf("TOOL", "x", null)).isNull();
    }

    @Test
    @DisplayName("PHASE: plan·sufficiency·judgment·verification 만, evidence.search 는 저장 안 함")
    void phases() {
        assertThat(AgentStepMapping.typeOf("PHASE", "evidence.plan", null)).isEqualTo(AgentStepType.PLAN);
        assertThat(AgentStepMapping.typeOf("PHASE", "evidence.sufficiency", null)).isEqualTo(AgentStepType.SUFFICIENCY);
        assertThat(AgentStepMapping.typeOf("PHASE", "judgment", null)).isEqualTo(AgentStepType.JUDGE);
        assertThat(AgentStepMapping.typeOf("PHASE", "verification", null)).isEqualTo(AgentStepType.VERIFY);
        assertThat(AgentStepMapping.typeOf("PHASE", "evidence.search", null)).isNull();
        assertThat(AgentStepMapping.typeOf("OTHER", "judgment", null)).isNull();
    }

    @Test
    @DisplayName("OK → DONE, ERROR → FAILED")
    void status() {
        assertThat(AgentStepMapping.statusOf("OK")).isEqualTo(AgentStepStatus.DONE);
        assertThat(AgentStepMapping.statusOf("ERROR")).isEqualTo(AgentStepStatus.FAILED);
    }
}
