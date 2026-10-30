package com.example.backend.agent.service;

import com.example.backend.agent.dto.AgentRunDetail;
import com.example.backend.agent.dto.AgentRunStarted;
import com.example.backend.agent.dto.StartWorkRegisterRequest;
import com.example.backend.agent.entity.AgentRun;
import com.example.backend.agent.entity.AgentRunStatus;
import com.example.backend.agent.repository.AgentRunRepository;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.permit.dto.UpdatePermitRequest;
import com.example.backend.permit.entity.Permit;
import com.example.backend.permit.entity.PermitStatus;
import com.example.backend.permit.entity.PermitType;
import com.example.backend.permit.repository.PermitMeasurementRepository;
import com.example.backend.permit.repository.PermitRepository;
import com.example.backend.permit.service.PermitService;
import com.example.backend.risk.entity.RiskAssessment;
import com.example.backend.risk.entity.RiskAssessmentItem;
import com.example.backend.risk.entity.RiskLevel;
import com.example.backend.risk.entity.RiskStatus;
import com.example.backend.risk.repository.RiskAssessmentItemRepository;
import com.example.backend.risk.repository.RiskAssessmentRepository;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.tbm.entity.TbmRecord;
import com.example.backend.tbm.entity.TbmStatus;
import com.example.backend.tbm.entity.TbmWork;
import com.example.backend.tbm.repository.TbmRecordRepository;
import com.example.backend.tbm.repository.TbmWorkRepository;
import com.example.backend.tbm.dto.TbmResponse;
import com.example.backend.tbm.service.TbmService;
import com.example.backend.risk.service.RiskAssessmentService;
import com.example.backend.work.dto.WorkListItem;
import com.example.backend.work.service.WorkService;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.work.entity.Work;
import com.example.backend.work.repository.WorkRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 작업 등록 DONE → 초안을 업무 DB 에 작성 중(DRAFT)으로 저장 (실제 DB, 끝나면 롤백).
 * 테스트는 커밋되지 않으므로 AI 서버 응답을 writer 에 직접 넣는다 (AgentRunFlowDbTest 와 같은 방식).
 */
@SpringBootTest
@Transactional
@DisplayName("에이전트 초안 → 업무 DB 저장")
class AgentDraftSaveDbTest {

    /** ai-model WorkRegisterResult(DONE) 모양. 화기 1건, 위험요인 2개, 점검표, TBM. %s = workDate 자리 */
    private static final String DONE = """
            {"runStatus": "DONE",
             "slots": {"utterances": ["내일 13시 화기작업", "R-201 하부, 3명, 15시까지"], "workDate": %s,
                       "location": "R-201 반응기 하부", "headcount": 3, "startTime": "13:00", "endTime": "15:00",
                       "measurements": {"LEL": 3}},
             "classification": {"workTypes": ["화기"], "highRisk": true, "requiredPermits": ["HOT_WORK_PERMIT"]},
             "drafts": {
               "riskAssessment": {"formType": "RISK_ASSESSMENT", "workSummary": "R-201 배관 플랜지 볼트 교체",
                 "location": "R-201 반응기 하부", "workTypes": ["화기"], "period": {"start": "13:00", "end": "15:00"},
                 "headcount": 3, "status": "DRAFT",
                 "items": [
                   {"seq": 1, "hazard": "그라인더 불티가 톨루엔 증기에 착화", "accidentTypes": ["화재", "폭발·파열"],
                    "riskFactor": "화학적", "frequency": 3, "severity": 4, "measures": ["가연성가스 측정", "불티 비산방지포"],
                    "residualFrequency": 1, "residualSeverity": 4, "citations": [], "needsReview": false, "source": "llm"},
                   {"seq": 2, "hazard": "고착 볼트 절단 중 공구 반동", "accidentTypes": ["절단·베임"],
                    "riskFactor": "기계적", "frequency": 2, "severity": 2, "measures": ["방호덮개 확인"],
                    "residualFrequency": 1, "residualSeverity": 2, "citations": [], "needsReview": true, "source": "rule"}]},
               "permits": [
                 {"formType": "HOT_WORK_PERMIT", "title": "화기작업허가서", "location": "R-201 반응기 하부",
                  "period": {"start": "13:00", "end": "15:00"}, "headcount": 3, "hotWorkKind": "그라인더 절단",
                  "measurements": [{"item": "LEL", "unit": "PCT_LEL", "value": 3, "status": "PASS", "criterion": "10 미만", "ruleCode": "HOT_LEL_MAX"}],
                  "assignments": [{"role": "FIRE_WATCHER", "label": "화재감시자", "assignee": "김감시", "status": "ASSIGNED"}],
                  "precautions": ["불티 비산방지포"], "riskItemSeqs": [1], "approvalBlocked": true,
                  "blockReasons": ["소화기 비치 확인 필요"], "status": "DRAFT"}],
               "inspectionChecklist": {"formType": "INSPECTION", "location": "R-201",
                 "items": [{"seq": 1, "item": "방호덮개", "target": "그라인더", "reason": "투입 장비"}], "status": "DRAFT"},
               "tbm": {"formType": "TBM", "points": [{"seq": 1, "point": "가스측정 결과 확인"}, {"seq": 2, "point": "화재감시자 지정"}],
                 "status": "DRAFT"}},
             "approvalGate": {"canRequest": false, "reasons": ["소화기 비치 확인 필요"]},
             "citations": [], "verification": [], "steps": []}
            """;

    @Autowired AgentRunService service;
    @Autowired AgentResultWriter writer;
    @Autowired AgentRunRepository runRepository;
    @Autowired WorkRepository workRepository;
    @Autowired RiskAssessmentRepository riskRepository;
    @Autowired RiskAssessmentItemRepository riskItemRepository;
    @Autowired PermitRepository permitRepository;
    @Autowired PermitMeasurementRepository measurementRepository;
    @Autowired PermitService permitService;
    @Autowired TbmRecordRepository tbmRepository;
    @Autowired TbmWorkRepository tbmWorkRepository;
    @Autowired RecordHistoryRepository historyRepository;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired JsonMapper jsonMapper;
    @Autowired WorkService workService;
    @Autowired TbmService tbmService;
    @Autowired RiskAssessmentService riskService;
    @Autowired EntityManager em;

    private Long userId;
    private Long siteId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        userId = userRepository.save(User.bootstrapAdmin("draft-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("초안현장-" + tag, SiteMode.VERIFY)).getId();
    }

    private AgentRunStarted send(Long conversationId, String message) {
        return service.startWorkRegister(userId, new StartWorkRegisterRequest(siteId, conversationId, null, message));
    }

    private JsonNode done(String workDate) {
        return jsonMapper.readTree(DONE.formatted(workDate == null ? "null" : "\"" + workDate + "\""));
    }

    /** 실행 하나를 DONE 으로 끝내고 다시 읽은 실행 */
    private AgentRun finish(AgentRunStarted started, JsonNode result) {
        writer.saveSuccess(started.runId(), result, 4000);
        em.flush();
        em.clear();
        return runRepository.findById(started.runId()).orElseThrow();
    }

    /** 실행 결과(output_json)의 저장 결과. DB JSON 컬럼은 공백을 바꿔 저장하므로 문자열 대신 트리로 비교 */
    private JsonNode saved(AgentRun run) {
        return jsonMapper.readTree(run.getOutputJson()).path("saved");
    }

    private Work workOf(AgentRun run) {
        assertThat(run.getTargetType()).isEqualTo("WORK");
        return workRepository.findById(run.getTargetId()).orElseThrow();
    }

    private Permit hotPermit(Work work) {
        return permitRepository.findTopByWorkIdAndPermitTypeOrderByRevisionDesc(work.getId(), PermitType.HOT).orElseThrow();
    }

    @Test
    @DisplayName("처음 DONE: 작업·위험성평가·화기 허가서·TBM 이 작성 중으로 저장되고 실행에 연결된다")
    void firstDone_createsDrafts() {
        AgentRun run = finish(send(null, "내일 13시 화기작업"), done("2026-11-02"));

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.DONE);

        // 작업: 날짜·종류·시각·인원
        Work work = workOf(run);
        assertThat(work.getWorkDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(work.getWorkType()).isEqualTo(WorkType.HOT);
        assertThat(work.getTitle()).isEqualTo("R-201 배관 플랜지 볼트 교체");
        assertThat(work.getStartAt()).isEqualTo(LocalDateTime.of(2026, 11, 2, 13, 0));
        assertThat(work.getEndAt()).isEqualTo(LocalDateTime.of(2026, 11, 2, 15, 0));
        assertThat(work.getWorkerCount()).isEqualTo(3);
        assertThat(work.getAgentRunId()).isEqualTo(run.getId());
        assertThat(work.getDescription()).contains("R-201 반응기 하부", "내일 13시 화기작업");

        // 위험성평가: 발생형태 코드(아는 첫 값), 등급(12 이상 HIGH), 대책 줄바꿈, 원문은 form_data
        RiskAssessment risk = riskRepository.findTopByWorkIdOrderByRevisionDesc(work.getId()).orElseThrow();
        assertThat(risk.getStatus()).isEqualTo(RiskStatus.DRAFT);
        assertThat(risk.getAgentRunId()).isEqualTo(run.getId());
        assertThat(risk.getFormData()).contains("needsReview", "화학적");
        List<RiskAssessmentItem> items = riskItemRepository.findByAssessmentIdOrderBySeqAsc(risk.getId());
        assertThat(items).extracting(RiskAssessmentItem::getAccidentType).containsExactly("FIRE", null);
        assertThat(items).extracting(RiskAssessmentItem::getRiskLevel).containsExactly(RiskLevel.HIGH, RiskLevel.LOW);
        assertThat(items.get(0).getControls()).isEqualTo("가연성가스 측정\n불티 비산방지포");
        assertThat(items.get(0).getResidualSeverity()).isEqualTo(4);

        // 허가서: 화재감시자, 기간, AI 초안은 form.draft, 측정값은 저장하지 않음
        Permit permit = hotPermit(work);
        assertThat(permit.getStatus()).isEqualTo(PermitStatus.DRAFT);
        assertThat(permit.getWatcherName()).isEqualTo("김감시");
        assertThat(permit.getValidFrom()).isEqualTo(LocalDateTime.of(2026, 11, 2, 13, 0));
        assertThat(permit.getAgentRunId()).isEqualTo(run.getId());
        assertThat(permit.getFormData()).contains("\"draft\"", "그라인더 절단");
        assertThat(measurementRepository.findByPermitIdAndPhaseOrderByMeasuredAtAscIdAsc(permit.getId(), "PRE_WORK")).isEmpty();

        // TBM: 같은 날짜, 교육 포인트, 작업 연결
        List<TbmWork> links = tbmWorkRepository.findByWorkIdOrderByTbmDateDesc(work.getId());
        assertThat(links).hasSize(1);
        TbmRecord tbm = tbmRepository.findById(links.get(0).getTbmId()).orElseThrow();
        assertThat(tbm.getStatus()).isEqualTo(TbmStatus.DRAFT);
        assertThat(tbm.getTbmDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(tbm.getEducationPoints()).contains("가스측정 결과 확인", "화재감시자 지정");

        // 이력: 작업·위험성평가·허가서·TBM 각각 AI_DRAFT, 행위자 = 실행
        for (HistoryTarget target : List.of(HistoryTarget.WORK, HistoryTarget.RISK, HistoryTarget.PERMIT, HistoryTarget.TBM)) {
            Long id = switch (target) {
                case WORK -> work.getId();
                case RISK -> risk.getId();
                case PERMIT -> permit.getId();
                default -> tbm.getId();
            };
            assertThat(historyRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAscIdAsc(target, id))
                    .extracting(RecordHistory::getAction, RecordHistory::getActorId)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(HistoryAction.AI_DRAFT, run.getId()));
        }

        // 실행 결과에 저장 결과(saved)와 점검표 미저장 안내
        JsonNode saved = saved(run);
        assertThat(saved.path("workId").asLong()).isEqualTo(work.getId());
        assertThat(saved.path("riskAssessmentId").asLong()).isEqualTo(risk.getId());
        assertThat(saved.path("permits").path(0).path("permitId").asLong()).isEqualTo(permit.getId());
        assertThat(saved.path("tbmId").asLong()).isEqualTo(tbm.getId());
        assertThat(saved.path("workDateAssumed").asBoolean()).isFalse();
        assertThat(saved.path("notes").toString()).contains("점검표");
    }

    @Test
    @DisplayName("같은 대화에서 다시 DONE: 새로 만들지 않고 덮어쓴다. 사람이 체크한 화기 확인 항목은 지킨다")
    void secondDone_overwritesKeepingHumanInput() {
        AgentRunStarted first = send(null, "내일 13시 화기작업");
        Work work = workOf(finish(first, done("2026-11-02")));
        Permit permit = hotPermit(work);

        // 사람이 허가 화면에서 소화기 비치를 체크
        Map<String, Object> form = new LinkedHashMap<>(jsonMapper.readValue(permit.getFormData(), Map.class));
        form.put("extinguisher_ready", true);
        permitService.update(userId, permit.getId(), new UpdatePermitRequest(permit.getValidFrom(), permit.getValidTo(),
                permit.getWatcherName(), null, form, permit.getLockVersion()));
        em.flush();
        em.clear();

        AgentRun second = finish(send(first.conversationId(), "14시부터로 바꿔줘"),
                jsonMapper.readTree(DONE.formatted("\"2026-11-02\"").replace("\"13:00\"", "\"14:00\"")));

        assertThat(second.getTargetId()).isEqualTo(work.getId());
        Work updated = workRepository.findById(work.getId()).orElseThrow();
        assertThat(updated.getStartAt()).isEqualTo(LocalDateTime.of(2026, 11, 2, 14, 0));

        assertThat(permitRepository.findAll()).filteredOn(p -> p.getWorkId().equals(work.getId())).hasSize(1);
        Permit again = hotPermit(work);
        assertThat(again.getId()).isEqualTo(permit.getId());
        assertThat(again.getValidFrom()).isEqualTo(LocalDateTime.of(2026, 11, 2, 14, 0));
        JsonNode againForm = jsonMapper.readTree(again.getFormData());
        assertThat(againForm.path("extinguisher_ready").asBoolean()).isTrue();
        assertThat(againForm.path("draft").path("period").path("start").asString()).isEqualTo("14:00");

        RiskAssessment risk = riskRepository.findTopByWorkIdOrderByRevisionDesc(work.getId()).orElseThrow();
        assertThat(riskRepository.findByWorkIdOrderByRevisionDesc(work.getId())).hasSize(1);
        assertThat(riskItemRepository.findByAssessmentIdOrderBySeqAsc(risk.getId())).hasSize(2);
        assertThat(tbmWorkRepository.findByWorkIdOrderByTbmDateDesc(work.getId())).hasSize(1);
    }

    @Test
    @DisplayName("승인 요청된 허가서는 다시 DONE 이 와도 바꾸지 않고 사유를 남긴다")
    void submittedPermit_notOverwritten() {
        AgentRunStarted first = send(null, "내일 13시 화기작업");
        Work work = workOf(finish(first, done("2026-11-02")));
        Permit permit = hotPermit(work);
        em.createNativeQuery("UPDATE permits SET status = 'PENDING' WHERE id = ?1").setParameter(1, permit.getId())
                .executeUpdate();
        em.clear();

        AgentRun second = finish(send(first.conversationId(), "14시부터로 바꿔줘"),
                jsonMapper.readTree(DONE.formatted("\"2026-11-02\"").replace("\"13:00\"", "\"14:00\"")));

        assertThat(second.getStatus()).isEqualTo(AgentRunStatus.DONE);
        assertThat(hotPermit(work).getValidFrom()).isEqualTo(LocalDateTime.of(2026, 11, 2, 13, 0));
        assertThat(saved(second).path("notes").toString()).contains("HOT 허가서가 이미 승인 요청·승인돼 바꾸지 않았습니다");
    }

    @Test
    @DisplayName("작업 날짜가 없으면 오늘로 저장하고 확인 안내를 남긴다")
    void noWorkDate_today() {
        AgentRun run = finish(send(null, "13시 화기작업"), done(null));

        Work work = workOf(run);
        assertThat(work.getWorkDate()).isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")));
        assertThat(saved(run).path("workDateAssumed").asBoolean()).isTrue();
        assertThat(saved(run).path("notes").toString()).contains("날짜를 확인해 주세요");
    }

    @Test
    @DisplayName("초안 값이 맞지 않으면(위험요인 없음) 아무것도 저장하지 않고 실행은 AI_BAD_RESPONSE 실패")
    void invalidDraft_nothingSaved() {
        long worksBefore = workRepository.count();
        ObjectNode bad = (ObjectNode) done("2026-11-02");
        ((ObjectNode) bad.path("drafts").path("riskAssessment")).putArray("items");   // 위험요인 없음
        AgentRun run = finish(send(null, "내일 13시 화기작업"), bad);

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.getErrorMessage()).startsWith("AI_BAD_RESPONSE:").contains("위험요인이 없습니다");
        assertThat(run.getTargetId()).isNull();
        assertThat(workRepository.count()).isEqualTo(worksBefore);
    }

    @Test
    @DisplayName("작업 시각이 없으면 허가서는 만들지 않고 사유를 남긴다 (작업·위험성평가·TBM 은 저장)")
    void noTime_permitSkipped() {
        String noTime = DONE.formatted("\"2026-11-02\"").replace("\"13:00\"", "null").replace("\"15:00\"", "null");
        AgentRun run = finish(send(null, "내일 화기작업"), jsonMapper.readTree(noTime));

        Work work = workOf(run);
        assertThat(work.getStartAt()).isNull();
        assertThat(permitRepository.findTopByWorkIdAndPermitTypeOrderByRevisionDesc(work.getId(), PermitType.HOT)).isEmpty();
        assertThat(riskRepository.findTopByWorkIdOrderByRevisionDesc(work.getId())).isPresent();
        assertThat(saved(run).path("notes").toString()).contains("시작·종료 시각이 없어 만들지 않았습니다");
    }

    // ------------------------------------------------------------------ 저장된 초안 조회 (웹이 기다리던 API)

    @Test
    @DisplayName("DONE 후 조회: 실행 상세의 현장·작업, 작업 목록의 허가서·위험성평가·TBM 상태, 위험성평가·TBM 상세")
    void lookups_afterDone() {
        AgentRunStarted started = send(null, "내일 13시 화기작업");
        Work work = workOf(finish(started, done("2026-11-02")));

        AgentRunDetail detail = service.getRun(userId, started.runId());
        assertThat(detail.siteId()).isEqualTo(siteId);
        assertThat(detail.workId()).isEqualTo(work.getId());
        assertThat(detail.permitId()).isNull();

        List<WorkListItem> list = workService.list(userId, siteId, LocalDate.of(2026, 11, 2), null);
        assertThat(list).singleElement().satisfies(item -> {
            assertThat(item.workId()).isEqualTo(work.getId());
            assertThat(item.permits()).singleElement().satisfies(p -> {
                assertThat(p.permitType()).isEqualTo(PermitType.HOT);
                assertThat(p.status()).isEqualTo(PermitStatus.DRAFT);
            });
            assertThat(item.riskAssessment().status()).isEqualTo(RiskStatus.DRAFT);
            assertThat(item.tbm().status()).isEqualTo(TbmStatus.DRAFT);
        });
        assertThat(workService.list(userId, siteId, LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 3))).isEmpty();

        assertThat(riskService.getByWork(userId, work.getId()).items()).hasSize(2);
        assertThat(riskService.getByWork(userId, work.getId()).canSubmit()).isTrue();

        TbmResponse tbm = tbmService.getByWork(userId, work.getId());
        assertThat(tbm.points()).containsExactly("가스측정 결과 확인", "화재감시자 지정");
        assertThat(tbm.works()).extracting(TbmResponse.WorkRef::workId).containsExactly(work.getId());
        assertThat(tbmService.listByDate(userId, siteId, LocalDate.of(2026, 11, 2)))
                .extracting(TbmResponse::tbmId).containsExactly(tbm.tbmId());
    }

    @Test
    @DisplayName("허가서를 연결해 보낸 실행은 실행 상세에 permitId 가 나온다")
    void runDetail_permitId() {
        AgentRunStarted first = send(null, "내일 13시 화기작업");
        Permit permit = hotPermit(workOf(finish(first, done("2026-11-02"))));

        AgentRunStarted second = service.startWorkRegister(userId,
                new StartWorkRegisterRequest(siteId, first.conversationId(), permit.getId(), "인원 4명으로"));

        assertThat(service.getRun(userId, second.runId()).permitId()).isEqualTo(permit.getId());
        assertThat(service.getRun(userId, second.runId()).workId()).isNull();   // 아직 처리 중
    }
}
