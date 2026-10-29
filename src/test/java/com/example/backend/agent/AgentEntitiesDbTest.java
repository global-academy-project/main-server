package com.example.backend.agent;

import com.example.backend.agent.entity.*;
import com.example.backend.agent.repository.*;
import com.example.backend.common.type.NetworkMode;
import com.example.backend.rule.entity.Rule;
import com.example.backend.rule.repository.RuleRepository;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 에이전트 엔티티를 실제 로컬 DB로 확인 (끝나면 롤백).
 * 대화 → 사용자 메시지 → 실행 → 단계(도구 호출) → 도구 메시지 → 근거(규칙·지침) → 되묻기 → 완료 → 피드백 → 요약
 * 각 단계가 V8 의 CHECK·외래키·유니크를 통과하는지 본다.
 */
@SpringBootTest
@Transactional
@DisplayName("에이전트 엔티티 DB 통합")
class AgentEntitiesDbTest {

    private static final String UUID_V5 = "6f1e2d3c-4b5a-5c6d-8e7f-0a1b2c3d4e5f";

    @Autowired AgentConversationRepository conversationRepository;
    @Autowired AgentMessageRepository messageRepository;
    @Autowired AgentRunRepository runRepository;
    @Autowired AgentStepRepository stepRepository;
    @Autowired EvidenceCitationRepository citationRepository;
    @Autowired AgentFeedbackRepository feedbackRepository;
    @Autowired RuleRepository ruleRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager em;

    @Test
    @DisplayName("대화 한 번의 흐름이 모든 제약을 통과해 저장되고 다시 읽힌다")
    void fullConversation() {
        LocalDateTime now = LocalDateTime.now();
        Long userId = userRepository.save(User.bootstrapAdmin(
                "agent-test-" + UUID.randomUUID() + "@example.com", "HASH", now)).getId();
        em.createNativeQuery("INSERT INTO sites (name) VALUES (?1)").setParameter(1, "테스트현장-" + UUID.randomUUID()).executeUpdate();
        Long siteId = ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
        Rule o2 = ruleRepository.findByEnabledTrueAndWorkTypeInOrderByRuleCodeAsc(List.of("CONFINED")).stream()
                .filter(r -> r.getRuleCode().equals("CONFINED_O2_RANGE")).findFirst().orElseThrow();

        // 대화 + 첫 사용자 메시지 (대화 행을 잠그고 순번)
        String text = "탱크 안에서 용접할 건데 산소 농도가 17.5%야";
        AgentConversation conv = conversationRepository.saveAndFlush(new AgentConversation(siteId, userId, text, null, null));
        AgentConversation locked = conversationRepository.findForUpdate(conv.getId()).orElseThrow();
        AgentMessage userMsg = messageRepository.save(AgentMessage.user(conv.getId(), locked.nextSeq(now), text, null));

        // 실행 (커밋 후 AI 서버 호출 가정)
        AgentRun run = runRepository.saveAndFlush(new AgentRun(siteId, userId, conv.getId(), AgentTaskType.PERMIT_DRAFT,
                AgentMode.VERIFY, NetworkMode.OPEN, "{\"text\":\"" + text + "\"}"));
        userMsg.linkRun(run.getId());

        // AI 서버가 돌려준 단계들
        AgentStep plan = stepRepository.save(new AgentStep(run.getId(), 1, AgentStepType.PLAN, null,
                null, "{\"workType\":\"CONFINED\"}", AgentStepStatus.DONE, 800));
        AgentStep tool = stepRepository.save(new AgentStep(run.getId(), 2, AgentStepType.TOOL_CALL, "check_rules",
                "{\"workType\":\"CONFINED\",\"input\":{\"measurement\":{\"O2\":17.5}}}", "{\"result\":\"BLOCK\"}",
                AgentStepStatus.DONE, 45));
        stepRepository.save(new AgentStep(run.getId(), 3, AgentStepType.ASK_USER, null,
                null, "{\"ask\":[\"measurement.CO2\",\"measurement.LEL\"]}", AgentStepStatus.DONE, 300));
        messageRepository.save(AgentMessage.tool(conv.getId(), locked.nextSeq(now), "check_rules", "call-1",
                "{\"result\":\"BLOCK\"}", run.getId()));

        // 근거: 규칙 + 지침 청크
        citationRepository.save(EvidenceCitation.fromRule(run.getId(), tool.getId(), o2.getId(), "산업안전보건기준에 관한 규칙",
                "제618조", null, "적정공기 산소 18% 이상 23.5% 미만"));
        citationRepository.save(EvidenceCitation.fromKb(run.getId(), plan.getId(), EvidenceSourceType.KOSHA_GUIDE,
                "kb_guides", UUID_V5, "밀폐공간 작업 프로그램", "8.5", null, "가연성 가스 폭발하한의 10% 미만"));

        // 되묻기 → 답을 기다림 (finished_at 없음)
        run.waitForInput("{\"ask\":true}", "test-model", null, 1500, 300, 120);
        messageRepository.save(AgentMessage.assistant(conv.getId(), locked.nextSeq(now),
                "탄산가스와 가연성 가스 측정값도 알려 주세요.", null, run.getId()));
        runRepository.flush();

        // 끝: 완료 + 피드백 + 요약
        run.complete("{\"done\":true}", "test-model", NetworkMode.OPEN, 2500, 400, 160, now);
        feedbackRepository.save(new AgentFeedback(run.getId(), userId, FeedbackRating.DOWN, FeedbackReason.MISSING_HAZARD, "용접 화재 위험 누락"));
        locked.updateSummary("탱크 내부 용접, 산소 17.5%로 차단, 추가 측정값 요청", 3);
        em.flush();
        em.clear();

        // 다시 읽기
        AgentConversation c = conversationRepository.findById(conv.getId()).orElseThrow();
        assertThat(c.getTitle()).isEqualTo(text);
        assertThat(c.getMessageCount()).isEqualTo(3);
        assertThat(c.getSummaryUntilSeq()).isEqualTo(3);
        assertThat(messageRepository.findByConversationIdOrderBySeqAsc(conv.getId()))
                .extracting(AgentMessage::getRole).containsExactly(MessageRole.USER, MessageRole.TOOL, MessageRole.ASSISTANT);
        assertThat(messageRepository.findByConversationIdAndSeqGreaterThanOrderBySeqAsc(conv.getId(), 1)).hasSize(2);

        AgentRun r = runRepository.findById(run.getId()).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(AgentRunStatus.DONE);
        assertThat(r.getFinishedAt()).isNotNull();
        assertThat(r.getInputTokens()).isEqualTo(400);
        assertThat(runRepository.findByConversationIdOrderByIdAsc(conv.getId())).hasSize(1);

        assertThat(stepRepository.findByRunIdOrderBySeqAsc(run.getId()))
                .extracting(AgentStep::getStepType)
                .containsExactly(AgentStepType.PLAN, AgentStepType.TOOL_CALL, AgentStepType.ASK_USER);
        assertThat(citationRepository.findByRunIdOrderByIdAsc(run.getId()))
                .extracting(EvidenceCitation::getSourceType)
                .containsExactly(EvidenceSourceType.RULE, EvidenceSourceType.KOSHA_GUIDE);
        assertThat(feedbackRepository.findByRunIdAndUserId(run.getId(), userId))
                .hasValueSatisfying(f -> assertThat(f.getReasonCode()).isEqualTo(FeedbackReason.MISSING_HAZARD));
        assertThat(conversationRepository.findByUserIdAndDeletedAtIsNullOrderByPinnedDescLastMessageAtDesc(userId))
                .extracting(AgentConversation::getId).containsExactly(conv.getId());
    }
}
