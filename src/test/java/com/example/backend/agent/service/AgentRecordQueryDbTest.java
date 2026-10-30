package com.example.backend.agent.service;

import com.example.backend.agent.client.MockAgentClient;
import com.example.backend.agent.client.RecordQueryCall;
import com.example.backend.agent.dto.AgentMessageItem;
import com.example.backend.agent.dto.AgentRunDetail;
import com.example.backend.agent.dto.AgentRunStarted;
import com.example.backend.agent.dto.StartRecordQueryRequest;
import com.example.backend.agent.dto.StartWorkRegisterRequest;
import com.example.backend.agent.entity.AgentRun;
import com.example.backend.agent.entity.AgentRunStatus;
import com.example.backend.agent.entity.AgentStepType;
import com.example.backend.agent.entity.AgentTaskType;
import com.example.backend.agent.entity.EvidenceSourceType;
import com.example.backend.agent.entity.MessageRole;
import com.example.backend.agent.repository.AgentRunRepository;
import com.example.backend.agent.repository.EvidenceCitationRepository;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.work.dto.CreateWorkRequest;
import com.example.backend.work.service.WorkService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기록 질문 실행 (POST /api/v1/agent/record-query): 시작 → AI 응답 저장(답변·조회 조건·근거) → 이어 묻기 (실제 DB, 끝나면 롤백).
 * 테스트는 커밋되지 않으므로 AI 서버 응답을 writer 에 직접 넣는다 (AgentRunFlowDbTest 와 같은 방식).
 */
@SpringBootTest
@Transactional
@DisplayName("기록 질문 실행 (실제 DB)")
class AgentRecordQueryDbTest {

    /** ai-model RecordQueryResult(DONE) 모양. %d = 근거로 쓸 이 현장 작업 id */
    private static final String DONE = """
            {"runStatus": "DONE",
             "answer": "최근 30일 TBM 없이 진행된 작업은 1건입니다.",
             "queries": [{"queryType": "missing_tbm", "conditions": {"siteId": 1, "from": "2026-10-01", "to": "2026-10-30"},
                          "total": 1, "truncated": false}],
             "evidence": [{"type": "WORK", "id": %d, "label": "R-201 배관 교체"},
                          {"type": "WORK", "id": %d, "label": "중복"},
                          {"type": "WORK", "id": 999999999, "label": "없는 작업"},
                          {"type": "SECRET_TABLE", "id": 1, "label": "모르는 종류"}],
             "context": {"lastQueryType": "missing_tbm"},
             "steps": [
               {"seq": 1, "kind": "PHASE", "name": "query.plan", "toolName": null, "status": "OK", "input": {}, "output": {}, "elapsedMs": 3},
               {"seq": 2, "kind": "TOOL", "name": "search_records", "toolName": "search_records", "status": "OK",
                "input": {"queryType": "missing_tbm"}, "output": {"total": 1}, "elapsedMs": 20},
               {"seq": 3, "kind": "PHASE", "name": "verification", "toolName": null, "status": "OK", "input": {}, "output": {}, "elapsedMs": 2}]}
            """;

    @Autowired AgentRunService service;
    @Autowired AgentResultWriter writer;
    @Autowired AgentRunRepository runRepository;
    @Autowired EvidenceCitationRepository citationRepository;
    @Autowired WorkService workService;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired JsonMapper jsonMapper;
    @Autowired EntityManager em;

    private Long userId;
    private Long siteId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        userId = userRepository.save(User.bootstrapAdmin("rq-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("질문현장-" + tag, SiteMode.GUIDE)).getId();
    }

    private AgentRunStarted ask(Long conversationId, String message) {
        return service.startRecordQuery(userId, new StartRecordQueryRequest(siteId, conversationId, message));
    }

    private Long work() {
        LocalDate d = LocalDate.now().minusDays(2);
        return workService.create(userId, new CreateWorkRequest(siteId, d, "R-201 배관 교체", null, WorkType.GENERAL,
                d.atTime(9, 0), d.atTime(11, 0), 2)).workId();
    }

    private void finish(Long runId, String json) {
        writer.saveSuccess(runId, jsonMapper.readTree(json), 900);
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("질문 → DONE: 실행 RECORD_QUERY, 답변 메시지, 조회 조건 단계, 근거는 이 현장에 있는 기록만 (중복·없는 id·모르는 종류 제외)")
    void done() {
        Long workId = work();
        AgentRunStarted started = ask(null, "최근 TBM 없이 한 작업 있어?");
        AgentRun run = runRepository.findById(started.runId()).orElseThrow();
        assertThat(run.getTaskType()).isEqualTo(AgentTaskType.RECORD_QUERY);
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.PROCESSING);
        assertThat(jsonMapper.readValue(run.getInputJson(), RecordQueryInput.class).context()).isNull();

        finish(started.runId(), DONE.formatted(workId, workId));

        AgentRunDetail d = service.getRun(userId, started.runId());
        assertThat(d.status()).isEqualTo(AgentRunStatus.DONE);
        assertThat(d.result().path("answer").asString()).isEqualTo("최근 30일 TBM 없이 진행된 작업은 1건입니다.");
        assertThat(d.result().path("queries").get(0).path("queryType").asString()).isEqualTo("missing_tbm");
        assertThat(d.steps()).extracting(AgentRunDetail.Step::type)
                .containsExactly(AgentStepType.PLAN, AgentStepType.TOOL_CALL, AgentStepType.VERIFY, AgentStepType.OUTPUT);
        assertThat(d.citations()).singleElement().satisfies(c -> {
            assertThat(c.sourceType()).isEqualTo(EvidenceSourceType.RECORD);
            assertThat(c.title()).isEqualTo("R-201 배관 교체");
            assertThat(c.locator()).isEqualTo("WORK:" + workId);
        });
        assertThat(citationRepository.findByRunIdOrderByIdAsc(started.runId()).get(0).getTargetId()).isEqualTo(workId);
        assertThat(d.workId()).isNull();   // 기록 질문은 업무 기록을 만들거나 고치지 않는다

        assertThat(service.getMessages(userId, started.conversationId()))
                .extracting(AgentMessageItem::role, AgentMessageItem::content)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(MessageRole.USER, "최근 TBM 없이 한 작업 있어?"),
                        org.assertj.core.groups.Tuple.tuple(MessageRole.ASSISTANT, "최근 30일 TBM 없이 진행된 작업은 1건입니다."));
    }

    @Test
    @DisplayName("이어 묻기: 직전 응답의 context 를 그대로 보낸다")
    void followUp_carriesContext() {
        Long workId = work();
        AgentRunStarted first = ask(null, "최근 TBM 없이 한 작업 있어?");
        finish(first.runId(), DONE.formatted(workId, workId));

        AgentRunStarted second = ask(first.conversationId(), "그중 화기작업만");

        assertThat(second.conversationId()).isEqualTo(first.conversationId());
        JsonNode context = jsonMapper.readValue(runRepository.findById(second.runId()).orElseThrow().getInputJson(),
                RecordQueryInput.class).context();
        assertThat(context.path("lastQueryType").asString()).isEqualTo("missing_tbm");
    }

    @Test
    @DisplayName("되묻기(WAITING_INPUT)는 ask 문장을 답변으로, 처리 중에는 다음 질문을 받지 않는다")
    void waitingAndBusy() {
        AgentRunStarted first = ask(null, "그거 몇 건이야?");
        expectCode(() -> ask(first.conversationId(), "또"), ErrorCode.INVALID_STATE);

        finish(first.runId(), """
                {"runStatus": "WAITING_INPUT", "ask": {"message": "어떤 기록을 말씀하시는지 알려 주세요."},
                 "queries": [], "evidence": [], "context": null, "steps": []}
                """);

        assertThat(service.getRun(userId, first.runId()).status()).isEqualTo(AgentRunStatus.WAITING_INPUT);
        assertThat(service.getMessages(userId, first.conversationId())).last()
                .extracting(AgentMessageItem::content).isEqualTo("어떤 기록을 말씀하시는지 알려 주세요.");
    }

    @Test
    @DisplayName("답변이 비었거나 모르는 상태면 실패 (AI_BAD_RESPONSE)")
    void badResponse() {
        AgentRunStarted empty = ask(null, "질문");
        finish(empty.runId(), "{\"runStatus\": \"DONE\", \"answer\": \" \", \"steps\": []}");
        assertThat(service.getRun(userId, empty.runId()).status()).isEqualTo(AgentRunStatus.FAILED);

        AgentRunStarted weird = ask(null, "질문");
        finish(weird.runId(), "{\"runStatus\": \"THINKING\", \"answer\": \"x\"}");
        assertThat(service.getRun(userId, weird.runId()).errorMessage()).startsWith("AI_BAD_RESPONSE");
    }

    @Test
    @DisplayName("작업 등록 대화와 기록 질문 대화는 섞이지 않는다 (탭마다 대화가 따로)")
    void conversationsDoNotMix() {
        AgentRunStarted register = service.startWorkRegister(userId,
                new StartWorkRegisterRequest(siteId, null, null, "오늘 용접 작업 있어"));
        AgentRunStarted query = ask(null, "지난주 승인 지연 몇 건?");
        finish(register.runId(), "{\"runStatus\": \"WAITING_INPUT\", \"slots\": {}, \"ask\": {\"message\": \"장소?\"}, \"steps\": []}");
        finish(query.runId(), "{\"runStatus\": \"DONE\", \"answer\": \"0건입니다.\", \"steps\": []}");

        expectCode(() -> ask(register.conversationId(), "기록 질문"), ErrorCode.INVALID_STATE);
        expectCode(() -> service.startWorkRegister(userId,
                new StartWorkRegisterRequest(siteId, query.conversationId(), null, "작업 등록")), ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("mock AI 서버: 질문을 되돌려 주는 DONE 답변 (조회 조건·단계 포함)")
    void mockClient() {
        JsonNode data = new MockAgentClient(jsonMapper).recordQuery(new RecordQueryCall(1L, 1L, "차단된 허가 있어?", null, "GUIDE")).data();
        assertThat(data.path("runStatus").asString()).isEqualTo("DONE");
        assertThat(data.path("answer").asString()).contains("[mock]", "차단된 허가 있어?");
        assertThat(data.path("queries").isArray()).isTrue();
        assertThat(data.path("steps")).hasSize(3);
    }
}
