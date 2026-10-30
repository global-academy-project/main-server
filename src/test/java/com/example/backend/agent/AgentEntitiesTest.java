package com.example.backend.agent;

import com.example.backend.agent.entity.*;
import com.example.backend.common.type.NetworkMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 에이전트 엔티티의 규칙 (DB CHECK 와 같은 내용을 저장 전에 막는다) */
@DisplayName("에이전트 엔티티 규칙")
class AgentEntitiesTest {

    private static final String UUID_V5 = "6f1e2d3c-4b5a-5c6d-8e7f-0a1b2c3d4e5f";

    @Test
    @DisplayName("대화 이름: 공백 정리, 100자까지, 비면 거부. context 는 type·id 함께")
    void conversationTitle() {
        assertThat(AgentConversation.titleOf("  오늘   13시에\n화기작업 ")).isEqualTo("오늘 13시에 화기작업");
        assertThat(AgentConversation.titleOf("가".repeat(150))).hasSize(100);
        assertThatThrownBy(() -> AgentConversation.titleOf("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentConversation(1L, 1L, "제목", ConversationContext.WORK, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("대화 순번: nextSeq 마다 1씩, 요약 범위는 1 ~ 메시지 수")
    void conversationSeqAndSummary() {
        AgentConversation c = new AgentConversation(1L, 1L, "제목", null, null);
        LocalDateTime now = LocalDateTime.of(2026, 10, 29, 9, 0);

        assertThat(c.nextSeq(now)).isEqualTo(1);
        assertThat(c.nextSeq(now)).isEqualTo(2);
        assertThat(c.getLastMessageAt()).isEqualTo(now);
        assertThatThrownBy(() -> c.updateSummary("요약", 3)).isInstanceOf(IllegalArgumentException.class);
        c.updateSummary("요약", 2);
        assertThat(c.getSummaryUntilSeq()).isEqualTo(2);
    }

    @Test
    @DisplayName("메시지: 본문 필수, 도구 메시지는 이름·호출 id 필수")
    void message() {
        assertThatThrownBy(() -> AgentMessage.user(1L, 1, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentMessage.tool(1L, 2, "check_rules", null, "{}", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AgentMessage.tool(1L, 2, "check_rules", "call-1", "{}", 5L).getRole()).isEqualTo(MessageRole.TOOL);
    }

    @Test
    @DisplayName("실행: 성공·실패는 종료 시각, 되묻기는 종료 시각 없음, 실패는 오류 내용 필수")
    void runStates() {
        AgentRun run = new AgentRun(1L, 1L, null, AgentTaskType.PERMIT_DRAFT, AgentMode.VERIFY, NetworkMode.OPEN, null);
        LocalDateTime now = LocalDateTime.of(2026, 10, 29, 9, 0);

        run.waitForInput("{}", "model-x", null, 1200, 100, 50);
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.WAITING_INPUT);
        assertThat(run.getFinishedAt()).isNull();
        assertThat(run.isFinished()).isFalse();

        run.complete("{}", "model-x", NetworkMode.CLOSED, 3000, 200, 80, now);
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.DONE);
        assertThat(run.getFinishedAt()).isEqualTo(now);
        assertThat(run.getNetworkMode()).isEqualTo(NetworkMode.CLOSED);

        assertThatThrownBy(() -> run.fail(" ", 10, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run.linkTarget("PERMIT", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("실행: 끝난(DONE·FAILED) 실행은 결과를 다시 쓸 수 없다 (늦은 응답·시간 초과 정리 덮어쓰기 방지)")
    void finishedRunIsFinal() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 30, 9, 0);
        AgentRun done = new AgentRun(1L, 1L, null, AgentTaskType.WORK_REGISTER, AgentMode.VERIFY, NetworkMode.OPEN, null);
        done.complete("{}", "model-x", null, 1000, null, null, now);
        assertThatThrownBy(() -> done.fail("AGENT_TIMEOUT: 응답 없음", 10, now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> done.waitForInput("{}", null, null, 10, null, null)).isInstanceOf(IllegalStateException.class);

        AgentRun failed = new AgentRun(1L, 1L, null, AgentTaskType.WORK_REGISTER, AgentMode.VERIFY, NetworkMode.OPEN, null);
        failed.fail("AGENT_TIMEOUT: 응답 없음", 180_000, now);
        assertThatThrownBy(() -> failed.complete("{}", null, null, 10, null, null, now)).isInstanceOf(IllegalStateException.class);
        assertThat(failed.getStatus()).isEqualTo(AgentRunStatus.FAILED);
    }

    @Test
    @DisplayName("단계: 도구 이름은 TOOL_CALL 에만, 정해진 도구만, 순번은 1부터")
    void step() {
        assertThatThrownBy(() -> new AgentStep(1L, 1, AgentStepType.TOOL_CALL, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentStep(1L, 1, AgentStepType.PLAN, "check_rules", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentStep(1L, 1, AgentStepType.TOOL_CALL, "drop_table", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentStep(1L, 0, AgentStepType.PLAN, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new AgentStep(1L, 1, AgentStepType.TOOL_CALL, "check_rules", "{}", "{}", null, 30).getStatus())
                .isEqualTo(AgentStepStatus.DONE);
    }

    @Test
    @DisplayName("근거: 검색 문서는 청크 id(UUID v5)·원문 필수, 규칙은 rule_id 필수")
    void citation() {
        assertThatThrownBy(() -> EvidenceCitation.of(1L, null, EvidenceSourceType.KOSHA_GUIDE, "지침", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvidenceCitation.fromKb(1L, null, EvidenceSourceType.KOSHA_GUIDE, "kb_guides",
                "not-a-uuid", "지침", null, null, "원문")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvidenceCitation.fromKb(1L, null, EvidenceSourceType.KOSHA_GUIDE, "kb_guides",
                UUID_V5, "지침", null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvidenceCitation.fromRule(1L, null, null, "규칙", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(EvidenceCitation.fromKb(1L, null, EvidenceSourceType.KOSHA_GUIDE, "kb_guides", UUID_V5,
                "용접·용단 화재예방", "7.2(1)", null, "작업 전 반드시 점검").getKbPointId()).isEqualTo(UUID_V5);
    }

    @Test
    @DisplayName("피드백: 싫어요는 이유 필수, 바꿀 수 있음")
    void feedback() {
        assertThatThrownBy(() -> new AgentFeedback(1L, 1L, FeedbackRating.DOWN, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        AgentFeedback f = new AgentFeedback(1L, 1L, FeedbackRating.DOWN, FeedbackReason.WRONG_RULE, "기준이 다름");
        f.change(FeedbackRating.UP, null, null);
        assertThat(f.getRating()).isEqualTo(FeedbackRating.UP);
        assertThat(f.getReasonCode()).isNull();
    }
}
