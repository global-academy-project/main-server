package com.example.backend.nearmiss.service;

import com.example.backend.agent.client.NearMissTagCall;
import com.example.backend.agent.dto.AgentRunStarted;
import com.example.backend.agent.entity.AgentRun;
import com.example.backend.agent.entity.AgentRunStatus;
import com.example.backend.agent.entity.AgentTaskType;
import com.example.backend.agent.repository.AgentRunRepository;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.nearmiss.dto.NearMissResponse;
import com.example.backend.nearmiss.dto.ReportNearMissRequest;
import com.example.backend.nearmiss.dto.SaveTagsRequest;
import com.example.backend.nearmiss.dto.SaveTagsRequest.Tag;
import com.example.backend.nearmiss.entity.NearMissStatus;
import com.example.backend.nearmiss.kb.NearMissKbDocuments;
import com.example.backend.site.dto.ZoneRequest;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMember;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.entity.SiteRole;
import com.example.backend.site.repository.SiteMemberRepository;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.site.service.ZoneService;
import com.example.backend.tag.entity.TagAxis;
import com.example.backend.tag.entity.TagSource;
import com.example.backend.user.entity.EmailVerifiedVia;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserRole;
import com.example.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 아차사고 자동 태깅 (실제 DB, 끝나면 롤백). 테스트는 한 트랜잭션이라 커밋 뒤 AI 호출(리스너)은 돌지 않는다.
 * AI 응답은 saveResult 에 직접 넣는다.
 */
@SpringBootTest
@Transactional
@DisplayName("아차사고 자동 태깅 (실제 DB)")
class NearMissAutoTagDbTest {

    @Autowired NearMissAutoTagService autoTag;
    @Autowired NearMissService service;
    @Autowired NearMissKbDocuments documents;
    @Autowired AgentRunRepository runRepository;
    @Autowired RecordHistoryRepository historyRepository;
    @Autowired ZoneService zoneService;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired SiteMemberRepository siteMemberRepository;
    @Autowired JsonMapper jsonMapper;

    private Long siteId;
    private Long reporter;
    private Long otherWriter;
    private Long zoneId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long adminId = userRepository.save(User.bootstrapAdmin("at-admin-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("자동태깅현장-" + tag, SiteMode.VERIFY)).getId();
        reporter = member("보고자", adminId);
        otherWriter = member("다른작성자", adminId);
        zoneId = zoneService.create(adminId, siteId, new ZoneRequest("T-101", "저장탱크", null, true, null, null)).zoneId();
    }

    private Long member(String name, Long adminId) {
        User u = new User("at-" + UUID.randomUUID() + "@example.com", "HASH", name);
        u.verifyEmail(EmailVerifiedVia.ADMIN, LocalDateTime.now());
        u.approve(UserRole.MEMBER);
        Long id = userRepository.save(u).getId();
        siteMemberRepository.save(new SiteMember(id, siteId, SiteRole.WRITER, adminId));
        return id;
    }

    private NearMissResponse report() {
        return service.report(reporter, new ReportNearMissRequest(siteId, zoneId, null, "맨홀 주변 미끄러짐",
                LocalDateTime.now().minusHours(1), "바닥 기름에 미끄러져 넘어질 뻔함", "접근 제한"));
    }

    private JsonNode reply(String tags, String source, String model) {
        return jsonMapper.readTree("{\"tags\": [" + tags + "], \"taggingSource\": \"" + source + "\", \"taggingVersion\": \"nm-1\","
                + " \"model\": " + (model == null ? "null" : "\"" + model + "\"") + "}");
    }

    @Test
    @DisplayName("시작: 보고자만(403), 버전이 다르면 409, 실행은 NEAR_MISS_TAG·대화 없음·대상 아차사고, 실행 중이면 다시 시작 409")
    void start() {
        NearMissResponse n = report();
        expectCode(() -> autoTag.start(otherWriter, n.nearMissId(), n.lockVersion()), ErrorCode.FORBIDDEN);
        expectCode(() -> autoTag.start(reporter, n.nearMissId(), n.lockVersion() + 1), ErrorCode.CONFLICT_MODIFIED);

        AgentRunStarted started = autoTag.start(reporter, n.nearMissId(), n.lockVersion());
        assertThat(started.conversationId()).isNull();
        assertThat(started.status()).isEqualTo(AgentRunStatus.PROCESSING);
        AgentRun run = runRepository.findById(started.runId()).orElseThrow();
        assertThat(run.getTaskType()).isEqualTo(AgentTaskType.NEAR_MISS_TAG);
        assertThat(run.getConversationId()).isNull();
        assertThat(run.getTargetType()).isEqualTo("NEAR_MISS");
        assertThat(run.getTargetId()).isEqualTo(n.nearMissId());
        assertThat(jsonMapper.readTree(run.getInputJson()).path("lockVersion").asInt()).isEqualTo(n.lockVersion());

        NearMissTagCall call = autoTag.call(run);
        assertThat(call.runId()).isEqualTo(run.getId());
        assertThat(call.nearMissId()).isEqualTo(n.nearMissId());
        assertThat(call.title()).isEqualTo("맨홀 주변 미끄러짐");
        assertThat(call.zoneName()).isEqualTo("저장탱크");
        assertThat(call.workType()).isNull();

        expectCode(() -> autoTag.start(reporter, n.nearMissId(), n.lockVersion()), ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("결과: 맞는 태그만 AI 출처로 저장(중상 제안·없는 코드·틀린 CAS·중복은 skipped), 상태 REPORTED·적재 안 함, 버전 증가, 실행 DONE")
    void saveResult() {
        NearMissResponse n = report();
        Long runId = autoTag.start(reporter, n.nearMissId(), n.lockVersion()).runId();

        autoTag.saveResult(runId, reply("""
                {"axis": "SEVERITY", "value": "NEAR_MISS", "confidence": 1.0},
                {"axis": "SEVERITY", "value": "SERIOUS", "confidence": 0.9},
                {"axis": "ACCIDENT_TYPE", "value": "TRIP", "confidence": 0.8},
                {"axis": "ACCIDENT_TYPE", "value": "TRIP", "confidence": 0.8},
                {"axis": "HAZARD", "value": "NO_SUCH_CODE", "confidence": 0.5},
                {"axis": "SUBSTANCE", "value": "108-88-3", "confidence": 1.0},
                {"axis": "SUBSTANCE", "value": "108-88-4", "confidence": 1.0},
                {"axis": "UNKNOWN", "value": "X", "confidence": 0.5}
                """, "near_miss_rules+llm", "Qwen/Qwen2.5-1.5B-Instruct"), 1200);

        NearMissResponse after = service.get(reporter, n.nearMissId());
        assertThat(after.status()).isEqualTo(NearMissStatus.REPORTED);
        assertThat(after.lockVersion()).isGreaterThan(n.lockVersion());
        assertThat(after.tags()).extracting(NearMissResponse.Tag::axis, NearMissResponse.Tag::value, NearMissResponse.Tag::source)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(TagAxis.SEVERITY, "NEAR_MISS", TagSource.AI),
                        org.assertj.core.groups.Tuple.tuple(TagAxis.ACCIDENT_TYPE, "TRIP", TagSource.AI),
                        org.assertj.core.groups.Tuple.tuple(TagAxis.SUBSTANCE, "108-88-3", TagSource.AI));
        assertThat(documents.document(n.nearMissId())).isEmpty();   // 제안뿐이라 적재하지 않음

        AgentRun run = runRepository.findById(runId).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.DONE);
        assertThat(run.getModel()).isEqualTo("Qwen/Qwen2.5-1.5B-Instruct");
        JsonNode out = jsonMapper.readTree(run.getOutputJson());
        assertThat(out.path("savedCount").asInt()).isEqualTo(3);
        assertThat(out.path("skipped").size()).isEqualTo(5);
        assertThat(out.path("lockVersion").asInt()).isEqualTo(after.lockVersion());
        assertThat(historyRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAscIdAsc(HistoryTarget.NEAR_MISS, n.nearMissId()))
                .anySatisfy(h -> {
                    assertThat(h.getAction()).isEqualTo(HistoryAction.AI_DRAFT);
                    assertThat(h.getActorId()).isEqualTo(runId);
                });
    }

    @Test
    @DisplayName("모델 실패 폴백 응답(규칙만, model null)도 그대로 저장. 사람이 AI 태그를 남겨 저장하면 확정·적재, 그 뒤 자동 태깅은 409")
    void rulesOnlyThenConfirm() {
        NearMissResponse n = report();
        Long runId = autoTag.start(reporter, n.nearMissId(), n.lockVersion()).runId();
        autoTag.saveResult(runId, reply("{\"axis\": \"SEVERITY\", \"value\": \"NEAR_MISS\", \"confidence\": 1.0}",
                "near_miss_rules", null), 30);
        assertThat(runRepository.findById(runId).orElseThrow().getModel()).isNull();

        NearMissResponse suggested = service.get(reporter, n.nearMissId());
        service.saveTags(reporter, n.nearMissId(), new SaveTagsRequest(suggested.lockVersion(), List.of(
                new Tag(TagAxis.SEVERITY, "NEAR_MISS", TagSource.AI, new BigDecimal("1.000")),
                new Tag(TagAxis.ACCIDENT_TYPE, "TRIP", TagSource.USER, null))));

        NearMissResponse confirmed = service.get(reporter, n.nearMissId());
        assertThat(confirmed.status()).isEqualTo(NearMissStatus.TAGGED);
        assertThat(documents.document(n.nearMissId())).isPresent();
        expectCode(() -> autoTag.start(reporter, n.nearMissId(), confirmed.lockVersion()), ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("실행 중에 사람이 태그를 저장하면 결과는 버리고 실행 FAILED (사람 태그 그대로)")
    void changedWhileRunning() {
        NearMissResponse n = report();
        Long runId = autoTag.start(reporter, n.nearMissId(), n.lockVersion()).runId();
        service.saveTags(reporter, n.nearMissId(), new SaveTagsRequest(n.lockVersion(), List.of(
                new Tag(TagAxis.ACCIDENT_TYPE, "FALL", TagSource.USER, null))));

        autoTag.saveResult(runId, reply("{\"axis\": \"ACCIDENT_TYPE\", \"value\": \"TRIP\", \"confidence\": 0.8}",
                "near_miss_rules", null), 30);

        AgentRun run = runRepository.findById(runId).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.getErrorMessage()).startsWith("NEAR_MISS_CHANGED");
        assertThat(service.get(reporter, n.nearMissId()).tags()).extracting(NearMissResponse.Tag::value).containsExactly("FALL");
    }

    @Test
    @DisplayName("글자 수는 코드 포인트로 자른다 (서로게이트 쌍을 가르지 않음)")
    void cut() {
        assertThat(NearMissAutoTagService.cut("가나다", 2)).isEqualTo("가나");
        assertThat(NearMissAutoTagService.cut("a😀b", 2)).isEqualTo("a😀");
        assertThat(NearMissAutoTagService.cut(null, 2)).isNull();
    }
}
