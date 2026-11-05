package com.example.backend.agent.service;

import com.example.backend.agent.dto.AgentConversationItem;
import com.example.backend.agent.dto.AgentRunStarted;
import com.example.backend.agent.dto.StartRecordQueryRequest;
import com.example.backend.agent.dto.StartWorkRegisterRequest;
import com.example.backend.agent.entity.AgentRunStatus;
import com.example.backend.agent.entity.AgentTaskType;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내 지난 대화 목록 (GET /api/v1/agent/conversations): 본인 · 현장 · 종류별, 최근 순, 마지막 실행 (실제 DB, 끝나면 롤백).
 * AI 서버 응답은 AgentRecordQueryDbTest 처럼 writer 에 직접 넣는다.
 */
@SpringBootTest
@Transactional
@DisplayName("지난 대화 목록 (실제 DB)")
class AgentConversationListDbTest {

    @Autowired AgentRunService service;
    @Autowired AgentResultWriter writer;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired JsonMapper jsonMapper;
    @Autowired EntityManager em;

    private Long userId;
    private Long otherUserId;
    private Long siteId;
    private Long otherSiteId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        userId = userRepository.save(User.bootstrapAdmin("cl-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        otherUserId = userRepository.save(User.bootstrapAdmin("cl2-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("목록현장-" + tag, SiteMode.GUIDE)).getId();
        otherSiteId = siteRepository.save(new Site("다른현장-" + tag, SiteMode.GUIDE)).getId();
    }

    private AgentRunStarted ask(Long user, Long site, Long conversationId, String message) {
        return service.startRecordQuery(user, new StartRecordQueryRequest(site, conversationId, message));
    }

    private void done(Long runId, String answer) {
        writer.saveSuccess(runId, jsonMapper.readTree("{\"runStatus\": \"DONE\", \"answer\": \"" + answer + "\", \"steps\": []}"), 500);
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("기록 질문 대화만, 최근 순, 제목·메시지 수·마지막 실행 (이어 물은 대화는 마지막 실행이 바뀐다)")
    void listsRecordQueries() {
        AgentRunStarted first = ask(userId, siteId, null, "지난달 TBM 안 한 작업은?");
        done(first.runId(), "3건입니다.");
        AgentRunStarted second = ask(userId, siteId, null, "이번 주 승인 대기 허가서");
        done(second.runId(), "2건입니다.");
        AgentRunStarted followUp = ask(userId, siteId, first.conversationId(), "그중 고위험만");   // 처리 중으로 둔다

        // 작업 등록 대화 · 다른 현장 · 다른 사람 대화는 빠진다
        service.startWorkRegister(userId, new StartWorkRegisterRequest(siteId, null, null, "오늘 용접 작업 있어"));
        ask(userId, otherSiteId, null, "다른 현장 질문");
        ask(otherUserId, siteId, null, "다른 사람 질문");
        em.flush();
        em.clear();

        List<AgentConversationItem> list = service.listConversations(userId, siteId, AgentTaskType.RECORD_QUERY, 30);

        assertThat(list).extracting(AgentConversationItem::conversationId)
                .containsExactly(first.conversationId(), second.conversationId());   // 방금 이어 물은 대화가 맨 위
        AgentConversationItem top = list.get(0);
        assertThat(top.title()).isEqualTo("지난달 TBM 안 한 작업은?");
        assertThat(top.taskType()).isEqualTo(AgentTaskType.RECORD_QUERY);
        assertThat(top.siteId()).isEqualTo(siteId);
        assertThat(top.messageCount()).isEqualTo(3);   // 질문 · 답 · 이어 묻기
        assertThat(top.lastRunId()).isEqualTo(followUp.runId());
        assertThat(top.lastRunStatus()).isEqualTo(AgentRunStatus.PROCESSING);
        assertThat(list.get(1).lastRunStatus()).isEqualTo(AgentRunStatus.DONE);
    }

    @Test
    @DisplayName("작업 등록 탭은 WORK_REGISTER 대화만, limit 은 1~50 으로 맞춘다")
    void workRegisterAndLimit() {
        AgentRunStarted register = service.startWorkRegister(userId, new StartWorkRegisterRequest(siteId, null, null, "오늘 용접 작업 있어"));
        ask(userId, siteId, null, "기록 질문");
        ask(userId, siteId, null, "기록 질문 2");
        em.flush();
        em.clear();

        assertThat(service.listConversations(userId, siteId, AgentTaskType.WORK_REGISTER, 30))
                .extracting(AgentConversationItem::conversationId).containsExactly(register.conversationId());
        assertThat(service.listConversations(userId, siteId, AgentTaskType.RECORD_QUERY, 0)).hasSize(1);
        assertThat(service.listConversations(userId, siteId, AgentTaskType.RECORD_QUERY, 999)).hasSize(2);
        assertThat(service.listConversations(userId, otherSiteId, AgentTaskType.RECORD_QUERY, 30)).isEmpty();
    }
}
