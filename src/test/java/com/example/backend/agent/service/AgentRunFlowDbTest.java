package com.example.backend.agent.service;

import com.example.backend.agent.dto.AgentMessageItem;
import com.example.backend.agent.dto.AgentRunDetail;
import com.example.backend.agent.dto.AgentRunStarted;
import com.example.backend.agent.dto.StartWorkRegisterRequest;
import com.example.backend.agent.entity.*;
import com.example.backend.agent.repository.AgentRunRepository;
import com.example.backend.common.exception.ErrorCode;
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

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 작업 등록 대화 한 번을 실제 DB로 확인 (끝나면 롤백).
 * 테스트는 트랜잭션이 커밋되지 않으므로 AI 서버 호출(커밋 후 리스너) 대신 AI 서버 응답을 writer 에 직접 넣는다.
 * 메시지 → 실행 저장 → 되묻기 응답 저장 → 같은 대화 다음 메시지(slots 이어 보내기) → 완료 응답 저장 → 조회
 */
@SpringBootTest
@Transactional
@DisplayName("에이전트 실행 연결 (작업 등록, 실제 DB)")
class AgentRunFlowDbTest {

    /** ai-model WorkRegisterResult 모양 (되묻기). slots 에 Spring 이 모르는 필드(askedKeys, future)가 있다 */
    private static final String ASK = """
            {"runStatus": "WAITING_INPUT",
             "slots": {"utterances": ["오늘 용접 작업 있어"], "askedKeys": ["location"], "future": {"x": 1}},
             "ask": {"message": "작업 장소를 알려 주세요.", "missing_fields": [{"key": "location", "label": "장소", "question": "어디?"}]},
             "citations": [], "verification": [],
             "steps": [{"seq": 1, "kind": "TOOL", "name": "classify_work", "toolName": "classify_work", "status": "OK",
                        "input": {"text": "용접"}, "output": {"workTypes": ["HOT"]}, "elapsedMs": 3}]}
            """;

    /** 완료. evidence.search 는 저장하지 않고, KB 근거는 출처 종류가 없어 저장하지 않는다 */
    private static final String DONE = """
            {"runStatus": "DONE",
             "slots": {"utterances": ["오늘 용접 작업 있어", "R-201 하부"], "askedKeys": []},
             "drafts": {"permits": []},
             "approvalGate": {"canRequest": false, "reasons": ["가연성 가스 측정값이 없습니다."]},
             "citations": [
               {"kind": "RULE", "refId": "HOT_LEL_MAX", "source": "화기작업 가연성 가스 기준", "snippet": "10 %LEL 미만"},
               {"kind": "MSDS", "refId": "108-88-3", "source": "톨루엔 MSDS", "snippet": "인화성 액체 구분 2"},
               {"kind": "KB", "refId": "6f1e2d3c-4b5a-5c6d-8e7f-0a1b2c3d4e5f", "source": "KOSHA GUIDE", "snippet": "…"},
               {"kind": "RULE", "refId": "NO_SUCH_RULE", "source": "없는 규칙", "snippet": "…"}],
             "verification": [],
             "steps": [
               {"seq": 1, "kind": "TOOL", "name": "classify_work", "toolName": "classify_work", "status": "OK", "input": {}, "output": {}, "elapsedMs": 2},
               {"seq": 2, "kind": "PHASE", "name": "evidence.plan", "toolName": null, "status": "OK", "input": {}, "output": {}, "elapsedMs": 5},
               {"seq": 3, "kind": "PHASE", "name": "evidence.search", "toolName": null, "status": "OK", "input": {}, "output": {}, "elapsedMs": 9},
               {"seq": 4, "kind": "TOOL", "name": "check_rules", "toolName": "check_rules", "status": "ERROR", "input": {}, "output": {"code": "TOOL_INPUT_INVALID"}, "elapsedMs": 4, "errorCode": "TOOL_INPUT_INVALID"},
               {"seq": 5, "kind": "PHASE", "name": "judgment", "toolName": null, "status": "OK", "input": {}, "output": {}, "elapsedMs": 7}]}
            """;

    @Autowired AgentRunService service;
    @Autowired AgentResultWriter writer;
    @Autowired AgentRunRepository runRepository;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired JsonMapper jsonMapper;
    @Autowired EntityManager em;

    private Long userId;
    private Long siteId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        userId = userRepository.save(User.bootstrapAdmin("agent-run-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("테스트현장-" + tag, SiteMode.GUIDE)).getId();
    }

    private AgentRunStarted send(Long conversationId, String message) {
        return service.startWorkRegister(userId, new StartWorkRegisterRequest(siteId, conversationId, null, message));
    }

    @Test
    @DisplayName("되묻기 → 같은 대화 다음 메시지(slots 그대로) → 완료: 실행·단계·근거·메시지가 저장되고 조회된다")
    void conversation() {
        // 1) 첫 메시지: 새 대화 + 실행 PROCESSING, 현장 모드(GUIDE)로 실행
        AgentRunStarted first = send(null, "오늘 용접 작업 있어");
        assertThat(first.status()).isEqualTo(AgentRunStatus.PROCESSING);
        AgentRun run1 = runRepository.findById(first.runId()).orElseThrow();
        assertThat(run1.getMode()).isEqualTo(AgentMode.GUIDE);
        assertThat(run1.getTaskType()).isEqualTo(AgentTaskType.WORK_REGISTER);
        assertThat(jsonMapper.readValue(run1.getInputJson(), RunInput.class).slots()).isNull();

        // 처리 중에는 같은 대화에 다음 메시지를 받지 않는다
        expectCode(() -> send(first.conversationId(), "또"), ErrorCode.INVALID_STATE);

        // 2) AI 서버 되묻기 응답 저장
        writer.saveSuccess(first.runId(), jsonMapper.readTree(ASK), 1200);
        em.flush();
        AgentRunDetail d1 = service.getRun(userId, first.runId());
        assertThat(d1.status()).isEqualTo(AgentRunStatus.WAITING_INPUT);
        assertThat(d1.steps()).extracting(AgentRunDetail.Step::type, AgentRunDetail.Step::toolName)
                .containsExactly(tuple(AgentStepType.TOOL_CALL, "classify_work"), tuple(AgentStepType.ASK_USER, null));
        assertThat(jsonMapper.writeValueAsString(d1.result())).contains("작업 장소를 알려 주세요.");

        // 3) 같은 대화 다음 메시지: 직전 응답의 slots 를 그대로 (모르는 필드 future 포함)
        AgentRunStarted second = send(first.conversationId(), "R-201 하부");
        assertThat(second.conversationId()).isEqualTo(first.conversationId());
        String input2 = runRepository.findById(second.runId()).orElseThrow().getInputJson();
        assertThat(input2).contains("askedKeys", "future");

        // 4) 완료 응답 저장
        writer.saveSuccess(second.runId(), jsonMapper.readTree(DONE), 4800);
        em.flush();
        em.clear();
        AgentRunDetail d2 = service.getRun(userId, second.runId());
        assertThat(d2.status()).isEqualTo(AgentRunStatus.DONE);
        assertThat(d2.finishedAt()).isNotNull();
        assertThat(d2.totalMs()).isEqualTo(4800);
        // evidence.search 는 빠지고 순번은 1부터 다시, 끝에 OUTPUT
        assertThat(d2.steps()).extracting(AgentRunDetail.Step::seq, AgentRunDetail.Step::type, AgentRunDetail.Step::status)
                .containsExactly(
                        tuple(1, AgentStepType.TOOL_CALL, AgentStepStatus.DONE),
                        tuple(2, AgentStepType.PLAN, AgentStepStatus.DONE),
                        tuple(3, AgentStepType.TOOL_CALL, AgentStepStatus.FAILED),
                        tuple(4, AgentStepType.JUDGE, AgentStepStatus.DONE),
                        tuple(5, AgentStepType.OUTPUT, AgentStepStatus.DONE));
        // 근거: 규칙(있는 코드)·MSDS 만 저장. KB(출처 종류 없음)·없는 규칙은 건너뜀
        assertThat(d2.citations()).extracting(AgentRunDetail.Citation::sourceType)
                .containsExactly(EvidenceSourceType.RULE, EvidenceSourceType.MSDS);
        // 응답 전체(drafts·approvalGate)는 result 에 그대로
        assertThat(jsonMapper.writeValueAsString(d2.result())).contains("drafts", "가연성 가스 측정값이 없습니다.");

        // 5) 대화: 사용자·답변이 번갈아 4개, 완료 답변에 승인 차단 사유
        List<AgentMessageItem> messages = service.getMessages(userId, first.conversationId());
        assertThat(messages).extracting(AgentMessageItem::seq, AgentMessageItem::role)
                .containsExactly(tuple(1, MessageRole.USER), tuple(2, MessageRole.ASSISTANT),
                        tuple(3, MessageRole.USER), tuple(4, MessageRole.ASSISTANT));
        assertThat(messages.get(1).content()).isEqualTo("작업 장소를 알려 주세요.");
        assertThat(messages.get(3).content()).contains("가연성 가스 측정값이 없습니다.");
    }

    @Test
    @DisplayName("AI 서버 실패: FAILED + 오류 코드, 실패 응답에 실린 단계도 저장, 다음 메시지는 직전 성공 slots 가 없으면 null")
    void failure() {
        AgentRunStarted run = send(null, "오늘 용접 작업 있어");
        writer.saveFailure(run.runId(), "AI_UNAVAILABLE", "AI 서버에 연결할 수 없습니다.",
                jsonMapper.readTree("[{\"seq\":1,\"kind\":\"TOOL\",\"name\":\"classify_work\",\"toolName\":\"classify_work\",\"status\":\"OK\"}]"),
                120000);
        em.flush();

        AgentRunDetail d = service.getRun(userId, run.runId());
        assertThat(d.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(d.errorMessage()).startsWith("AI_UNAVAILABLE:");
        assertThat(d.finishedAt()).isNotNull();
        assertThat(d.steps()).hasSize(1);
        assertThat(service.getMessages(userId, run.conversationId())).hasSize(2);

        // 실패 뒤에는 같은 대화에 다시 보낼 수 있고, slots 는 비어 있다
        AgentRunStarted retry = send(run.conversationId(), "다시");
        assertThat(jsonMapper.readValue(runRepository.findById(retry.runId()).orElseThrow().getInputJson(), RunInput.class)
                .slots()).isNull();
    }

    @Test
    @DisplayName("이미 끝난 실행에 결과가 다시 오면 무시한다")
    void idempotent() {
        AgentRunStarted run = send(null, "오늘 용접 작업 있어");
        writer.saveSuccess(run.runId(), jsonMapper.readTree(ASK), 10);
        writer.saveSuccess(run.runId(), jsonMapper.readTree(DONE), 10);   // 무시
        em.flush();

        assertThat(service.getRun(userId, run.runId()).status()).isEqualTo(AgentRunStatus.WAITING_INPUT);
    }

    @Test
    @DisplayName("다른 사람의 실행·대화는 볼 수 없다 (NOT_FOUND)")
    void ownerOnly() {
        AgentRunStarted run = send(null, "오늘 용접 작업 있어");
        Long other = userRepository.save(User.bootstrapAdmin("agent-other-" + UUID.randomUUID() + "@example.com",
                "HASH", LocalDateTime.now())).getId();

        expectCode(() -> service.getRun(other, run.runId()), ErrorCode.NOT_FOUND);
        expectCode(() -> service.getMessages(other, run.conversationId()), ErrorCode.NOT_FOUND);
        expectCode(() -> service.startWorkRegister(other,
                new StartWorkRegisterRequest(siteId, run.conversationId(), null, "남의 대화")), ErrorCode.NOT_FOUND);
    }
}
