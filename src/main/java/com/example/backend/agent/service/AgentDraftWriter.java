package com.example.backend.agent.service;

import com.example.backend.agent.entity.AgentRun;
import com.example.backend.agent.repository.AgentRunRepository;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.permit.dto.CreatePermitRequest;
import com.example.backend.permit.dto.PermitResponse;
import com.example.backend.permit.dto.UpdatePermitRequest;
import com.example.backend.permit.entity.Permit;
import com.example.backend.permit.entity.PermitType;
import com.example.backend.permit.repository.PermitRepository;
import com.example.backend.permit.service.PermitService;
import com.example.backend.risk.entity.RiskAssessment;
import com.example.backend.risk.entity.RiskAssessmentItem;
import com.example.backend.risk.entity.RiskLevel;
import com.example.backend.risk.entity.RiskMethod;
import com.example.backend.risk.entity.RiskStatus;
import com.example.backend.risk.repository.RiskAssessmentItemRepository;
import com.example.backend.risk.repository.RiskAssessmentRepository;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.tbm.entity.TbmRecord;
import com.example.backend.tbm.entity.TbmStatus;
import com.example.backend.tbm.entity.TbmWork;
import com.example.backend.tbm.repository.TbmRecordRepository;
import com.example.backend.tbm.repository.TbmWorkRepository;
import com.example.backend.work.dto.CreateWorkRequest;
import com.example.backend.work.entity.Work;
import com.example.backend.work.repository.WorkRepository;
import com.example.backend.work.service.WorkService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 작업 등록 에이전트가 DONE 으로 끝나면 초안을 업무 DB 에 작성 중(DRAFT)으로 저장한다 (계획서 7-2장 확정 사항).
 * AgentResultWriter.saveSuccess 와 같은 트랜잭션에서 실행되므로, 저장 중 오류면 실행 결과까지 함께 롤백된다.
 *
 * <ul>
 *   <li>처음: 작업 → 위험성평가(일일) → 작업허가서(종류별) → TBM 을 새로 만든다</li>
 *   <li>같은 대화에서 다시 DONE: 앞서 만든 기록이 아직 고칠 수 있는 상태면 덮어쓰고, 아니면 그대로 두고 사유를 남긴다
 *       (반려 후 새 판은 승인 API 와 함께 만든다)</li>
 *   <li>점검표 초안은 저장하지 않는다 (점검 대상 구역·장비 id 가 없음. 2주차 점검표 API 에서)</li>
 *   <li>대화에서 말한 측정값은 저장하지 않는다 (측정은 작업 직전 현장에서 사람이 입력, 7-3장)</li>
 *   <li>모든 저장·수정은 이력에 AI_DRAFT 로 남는다 (행위자 = 실행 id)</li>
 * </ul>
 */
@Component
public class AgentDraftWriter {

    /** ai-model 발생형태(한글) → tag_codes ACCIDENT_TYPE. 없는 이름은 null 로 두고 원문은 form_data 에 남는다 */
    static final Map<String, String> ACCIDENT_CODES = Map.of(
            "화재", "FIRE",
            "폭발·파열", "EXPLOSION",
            "누출·접촉", "LEAK",
            "산소결핍", "ASPHYXIATION",
            "떨어짐", "FALL",
            "넘어짐", "TRIP",
            "끼임", "CAUGHT",
            "맞음", "STRUCK",
            "감전", "ELECTRIC");

    private static final int MAX_WORK_STEP = 100;
    private static final int MAX_TITLE = 200;
    private static final int MAX_NAME = 50;

    private final WorkService workService;
    private final WorkRepository workRepository;
    private final PermitService permitService;
    private final PermitRepository permitRepository;
    private final RiskAssessmentRepository riskRepository;
    private final RiskAssessmentItemRepository riskItemRepository;
    private final TbmRecordRepository tbmRepository;
    private final TbmWorkRepository tbmWorkRepository;
    private final RecordHistoryRepository historyRepository;
    private final AgentRunRepository runRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final int highMinScore;
    private final int mediumMinScore;

    public AgentDraftWriter(WorkService workService, WorkRepository workRepository, PermitService permitService,
                            PermitRepository permitRepository, RiskAssessmentRepository riskRepository,
                            RiskAssessmentItemRepository riskItemRepository, TbmRecordRepository tbmRepository,
                            TbmWorkRepository tbmWorkRepository, RecordHistoryRepository historyRepository,
                            AgentRunRepository runRepository, JsonMapper jsonMapper, Clock clock,
                            @Value("${app.risk.level.high-min-score}") int highMinScore,
                            @Value("${app.risk.level.medium-min-score}") int mediumMinScore) {
        if (mediumMinScore < 1 || highMinScore <= mediumMinScore) {
            throw new IllegalStateException("app.risk.level: 1 ≤ medium-min-score < high-min-score 여야 합니다.");
        }
        this.workService = workService;
        this.workRepository = workRepository;
        this.permitService = permitService;
        this.permitRepository = permitRepository;
        this.riskRepository = riskRepository;
        this.riskItemRepository = riskItemRepository;
        this.tbmRepository = tbmRepository;
        this.tbmWorkRepository = tbmWorkRepository;
        this.historyRepository = historyRepository;
        this.runRepository = runRepository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.highMinScore = highMinScore;
        this.mediumMinScore = mediumMinScore;
    }

    /**
     * DONE 응답의 초안을 저장한다. 저장할 초안이 없으면 아무것도 하지 않는다.
     *
     * @param permitIdHint 대화가 묶인 허가서 (실행 요청의 permitId). 있으면 그 허가서의 작업에 저장한다
     * @throws DraftInvalidException 초안 값이 업무 규칙에 맞지 않을 때 (아무것도 저장하기 전에 확인)
     */
    public SavedDrafts save(AgentRun run, JsonNode data, Long permitIdHint) {
        DraftPayload payload;
        try {
            payload = jsonMapper.treeToValue(data, DraftPayload.class);
        } catch (JacksonException e) {
            throw new DraftInvalidException("초안 형식을 읽을 수 없습니다.");
        }
        DraftPayload.Drafts drafts = payload.drafts();
        if (drafts == null || (drafts.riskAssessment() == null && isEmpty(drafts.permits()) && drafts.tbm() == null)) {
            return SavedDrafts.nothing();
        }
        Plan plan = plan(payload, data.path("drafts"));   // 저장 전에 모두 확인

        List<String> notes = new ArrayList<>(plan.notes());
        Work work = saveWork(run, plan, permitIdHint, notes);
        Long riskId = plan.risk() == null ? null : saveRisk(run, work, plan, notes);
        List<SavedDrafts.SavedPermit> permits = new ArrayList<>();
        for (PermitPlan p : plan.permits()) {
            savePermit(run, work, p, notes).ifPresent(permits::add);
        }
        Long tbmId = plan.tbmPoints() == null ? null : saveTbm(run, work, plan, notes);
        return new SavedDrafts(work.getId(), riskId, permits, tbmId, plan.workDateAssumed(), notes);
    }

    // ------------------------------------------------------------------ 확인 (DB 쓰기 전)

    record Plan(LocalDate workDate, boolean workDateAssumed, String title, String description, WorkType workType,
                LocalDateTime startAt, LocalDateTime endAt, Integer workerCount,
                List<RiskItemPlan> risk, String riskFormJson, List<PermitPlan> permits,
                List<String> tbmPoints, List<String> notes) {
    }

    record RiskItemPlan(int seq, String workStep, String hazard, String accidentType, int frequency, int severity,
                        RiskLevel level, String controls, Integer residualFrequency, Integer residualSeverity) {
    }

    record PermitPlan(PermitType type, LocalDateTime validFrom, LocalDateTime validTo, String watcherName,
                      String supervisorName, Map<String, Object> draftJson) {
    }

    Plan plan(DraftPayload payload, JsonNode draftsNode) {
        DraftPayload.Drafts d = payload.drafts();
        DraftPayload.Slots slots = payload.slots();
        List<String> notes = new ArrayList<>();

        // 작업 날짜: AI 서버가 slots.workDate 로 준다. 없으면 오늘로 두고 화면에서 확인하게 한다
        LocalDate today = LocalDate.now(clock);
        LocalDate workDate = parseDate(slots == null ? null : slots.workDate());
        boolean assumed = workDate == null;
        if (assumed) {
            workDate = today;
            notes.add("작업 날짜를 알 수 없어 오늘(" + today + ")로 저장했습니다. 날짜를 확인해 주세요.");
        }

        DraftPayload.RiskAssessment risk = d.riskAssessment();
        DraftPayload.Permit firstPermit = isEmpty(d.permits()) ? null : d.permits().get(0);
        String title = firstNonBlank(risk == null ? null : risk.workSummary(), firstPermit == null ? null : firstPermit.title(),
                "에이전트 작업 등록");
        String location = firstNonBlank(risk == null ? null : risk.location(), firstPermit == null ? null : firstPermit.location(),
                slots == null ? null : slots.location());
        DraftPayload.Period period = firstPeriod(risk == null ? null : risk.period(),
                firstPermit == null ? null : firstPermit.period(),
                slots == null ? null : new DraftPayload.Period(slots.startTime(), slots.endTime()));
        LocalDateTime[] range = range(workDate, period, notes, "작업");
        Integer headcount = firstPositive(risk == null ? null : risk.headcount(), firstPermit == null ? null : firstPermit.headcount(),
                slots == null ? null : slots.headcount());

        WorkType workType = workTypeOf(d.permits(), payload.classification());
        String description = describe(location, slots);

        List<RiskItemPlan> riskItems = null;
        String riskForm = null;
        if (risk != null) {
            if (isEmpty(risk.items())) {
                throw new DraftInvalidException("위험성평가 초안에 위험요인이 없습니다.");
            }
            riskItems = new ArrayList<>();
            String workStep = truncate(title, MAX_WORK_STEP);
            for (DraftPayload.RiskItem it : risk.items()) {
                riskItems.add(riskItem(it, workStep));
            }
            riskForm = jsonMapper.writeValueAsString(draftsNode.path("riskAssessment"));
        }

        List<PermitPlan> permits = new ArrayList<>();
        if (d.permits() != null) {
            for (int i = 0; i < d.permits().size(); i++) {
                DraftPayload.Permit p = d.permits().get(i);
                PermitType type = permitTypeOf(p.formType());
                LocalDateTime[] pr = range(workDate, firstPeriod(p.period(), period), notes, type.name() + " 허가서");
                if (pr[0] == null || pr[1] == null) {
                    notes.add(type.name() + " 허가서: 작업 시작·종료 시각이 없어 만들지 않았습니다. 시각을 알려 주시면 다시 만듭니다.");
                    continue;
                }
                Map<String, Object> draftJson = jsonMapper.convertValue(draftsNode.path("permits").path(i), Map.class);
                permits.add(new PermitPlan(type, pr[0], pr[1], assignee(p, watcherRoleOf(type)), assignee(p, "SUPERVISOR"),
                        draftJson));
            }
        }

        List<String> tbmPoints = null;
        if (d.tbm() != null) {
            if (isEmpty(d.tbm().points())) {
                throw new DraftInvalidException("TBM 초안에 교육 포인트가 없습니다.");
            }
            tbmPoints = d.tbm().points().stream().map(DraftPayload.TbmPoint::point)
                    .filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
        }
        if (d.inspectionChecklist() != null) {
            notes.add("점검표 초안은 아직 저장하지 않습니다 (점검 구역·장비를 고르는 기능이 생기면 저장).");
        }
        return new Plan(workDate, assumed, truncate(title, MAX_TITLE), description, workType, range[0], range[1], headcount,
                riskItems, riskForm, permits, tbmPoints, notes);
    }

    private RiskItemPlan riskItem(DraftPayload.RiskItem it, String workStep) {
        if (it.seq() == null || it.frequency() == null || it.severity() == null || isBlank(it.hazard())
                || isEmpty(it.measures())) {
            throw new DraftInvalidException("위험성평가 위험요인에 순번·위험요인·빈도·강도·감소대책이 필요합니다.");
        }
        if ((it.residualFrequency() == null) != (it.residualSeverity() == null)) {
            throw new DraftInvalidException("개선 후 빈도와 강도는 함께 있어야 합니다.");
        }
        String accident = isEmpty(it.accidentTypes()) ? null
                : it.accidentTypes().stream().map(ACCIDENT_CODES::get).filter(Objects::nonNull).findFirst().orElse(null);
        String controls = String.join("\n", it.measures().stream().filter(s -> s != null && !s.isBlank()).toList());
        return new RiskItemPlan(it.seq(), workStep, it.hazard().trim(), accident, it.frequency(), it.severity(),
                levelOf(it.frequency() * it.severity()), controls, it.residualFrequency(), it.residualSeverity());
    }

    /** 빈도×강도 → 등급. 기준은 yaml app.risk.level (임시, 실무자 검증 때 조정) */
    RiskLevel levelOf(int score) {
        if (score >= highMinScore) return RiskLevel.HIGH;
        if (score >= mediumMinScore) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    // ------------------------------------------------------------------ 저장

    private Work saveWork(AgentRun run, Plan plan, Long permitIdHint, List<String> notes) {
        Work existing = findExistingWork(run, permitIdHint);
        if (existing == null) {
            Long workId = workService.create(run.getUserId(), new CreateWorkRequest(run.getSiteId(), plan.workDate(),
                    plan.title(), plan.description(), plan.workType(), plan.startAt(), plan.endAt(), plan.workerCount())).workId();
            Work work = workRepository.findById(workId).orElseThrow();
            work.linkRun(run.getId());
            history(run, HistoryTarget.WORK, workId, "CREATE");
            return work;
        }
        if (existing.isEditable()) {
            existing.updateContent(plan.workDate(), plan.title(), plan.description(), plan.workType(),
                    plan.startAt(), plan.endAt(), plan.workerCount());
            existing.linkRun(run.getId());
            history(run, HistoryTarget.WORK, existing.getId(), "UPDATE");
        } else {
            notes.add("작업이 이미 승인·진행 중이라 작업 내용은 바꾸지 않았습니다.");
        }
        return existing;
    }

    /** 대화가 묶인 허가서의 작업, 아니면 같은 대화의 앞선 실행이 만든 작업 */
    private Work findExistingWork(AgentRun run, Long permitIdHint) {
        if (permitIdHint != null) {
            Optional<Work> byPermit = permitRepository.findById(permitIdHint)
                    .flatMap(p -> workRepository.findById(p.getWorkId()));
            if (byPermit.isPresent()) {
                return byPermit.get();
            }
        }
        if (run.getConversationId() == null) {
            return null;
        }
        List<AgentRun> runs = runRepository.findByConversationIdOrderByIdAsc(run.getConversationId());
        for (int i = runs.size() - 1; i >= 0; i--) {
            AgentRun r = runs.get(i);
            if (!r.getId().equals(run.getId()) && "WORK".equals(r.getTargetType()) && r.getTargetId() != null) {
                Optional<Work> w = workRepository.findById(r.getTargetId());
                if (w.isPresent() && w.get().getSiteId().equals(run.getSiteId())) {
                    return w.get();
                }
            }
        }
        return null;
    }

    private Long saveRisk(AgentRun run, Work work, Plan plan, List<String> notes) {
        RiskAssessment risk = riskRepository.findTopByWorkIdOrderByRevisionDesc(work.getId()).orElse(null);
        String action = "UPDATE";
        if (risk == null) {
            risk = riskRepository.saveAndFlush(RiskAssessment.daily(work.getSiteId(), work.getId(), plan.workDate(),
                    run.getUserId()));
            action = "CREATE";
        } else if (risk.getStatus() != RiskStatus.DRAFT) {
            notes.add("위험성평가가 이미 승인 요청·승인돼 바꾸지 않았습니다.");
            return risk.getId();
        } else {
            riskItemRepository.deleteAll(riskItemRepository.findByAssessmentIdOrderBySeqAsc(risk.getId()));
            riskItemRepository.flush();
        }
        risk.updateContent(null, null, plan.workDate(), RiskMethod.FREQ_SEVERITY, "v1", plan.riskFormJson());
        risk.linkRun(run.getId());
        for (RiskItemPlan it : plan.risk()) {
            riskItemRepository.save(new RiskAssessmentItem(risk.getId(), it.seq(), it.workStep(), it.hazard(),
                    it.accidentType(), it.frequency(), it.severity(), it.level(), it.controls(),
                    it.residualFrequency(), it.residualSeverity()));
        }
        history(run, HistoryTarget.RISK, risk.getId(), action);
        return risk.getId();
    }

    private Optional<SavedDrafts.SavedPermit> savePermit(AgentRun run, Work work, PermitPlan p, List<String> notes) {
        Permit existing = permitRepository.findTopByWorkIdAndPermitTypeOrderByRevisionDesc(work.getId(), p.type())
                .orElse(null);
        PermitResponse saved;
        boolean created;
        if (existing == null) {
            Map<String, Object> form = new LinkedHashMap<>();
            form.put("draft", p.draftJson());
            saved = permitService.create(run.getUserId(), new CreatePermitRequest(work.getId(), p.type(), p.validFrom(),
                    p.validTo(), p.watcherName(), p.supervisorName(), form));
            created = true;
        } else if (existing.getStatus().isEditable()) {
            // 사람이 넣은 값(화기 확인 3항목 등)은 지키고 AI 초안 부분("draft")만 바꾼다. 이름은 AI 가 새로 준 경우만
            Map<String, Object> form = existing.getFormData() == null ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(jsonMapper.readValue(existing.getFormData(), Map.class));
            form.put("draft", p.draftJson());
            saved = permitService.update(run.getUserId(), existing.getId(), new UpdatePermitRequest(p.validFrom(), p.validTo(),
                    p.watcherName() != null ? p.watcherName() : existing.getWatcherName(),
                    p.supervisorName() != null ? p.supervisorName() : existing.getSupervisorName(),
                    form, existing.getLockVersion()));
            created = false;
        } else {
            notes.add(p.type().name() + " 허가서가 이미 승인 요청·승인돼 바꾸지 않았습니다.");
            return Optional.of(new SavedDrafts.SavedPermit(existing.getId(), p.type().name(), existing.getPermitNo(), false));
        }
        permitRepository.findById(saved.permitId()).ifPresent(permit -> permit.linkRun(run.getId()));
        history(run, HistoryTarget.PERMIT, saved.permitId(), created ? "CREATE" : "UPDATE");
        return Optional.of(new SavedDrafts.SavedPermit(saved.permitId(), p.type().name(), saved.permitNo(), created));
    }

    private Long saveTbm(AgentRun run, Work work, Plan plan, List<String> notes) {
        String points = jsonMapper.writeValueAsString(plan.tbmPoints());
        List<TbmWork> links = tbmWorkRepository.findByWorkIdOrderByTbmDateDesc(work.getId());
        if (!links.isEmpty()) {
            TbmRecord tbm = tbmRepository.findById(links.get(0).getTbmId()).orElseThrow();
            if (tbm.getStatus() != TbmStatus.DRAFT) {
                notes.add("TBM 이 이미 실시 완료·확인돼 교육 포인트를 바꾸지 않았습니다.");
                return tbm.getId();
            }
            tbm.updateContent(tbm.getTeamId(), tbm.getZoneId(), tbm.getTbmDate(), tbm.getLeaderName(), points,
                    tbm.getHealthCheck());
            tbm.linkRun(run.getId());
            history(run, HistoryTarget.TBM, tbm.getId(), "UPDATE");
            return tbm.getId();
        }
        TbmRecord tbm = new TbmRecord(work.getSiteId(), plan.workDate(), null, null, run.getUserId());
        tbm.updateContent(null, null, plan.workDate(), null, points, null);
        tbm.linkRun(run.getId());
        tbm = tbmRepository.saveAndFlush(tbm);
        tbmWorkRepository.save(TbmWork.of(tbm, work));
        history(run, HistoryTarget.TBM, tbm.getId(), "CREATE");
        return tbm.getId();
    }

    /** 에이전트 초안 이력. 행위자 = 실행 id, 내용은 이 실행에서 무엇을 했는지만 (본문은 기록 자체에 있음) */
    private void history(AgentRun run, HistoryTarget target, Long id, String action) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("agentRunId", run.getId());
        after.put("draft", action);
        historyRepository.save(RecordHistory.byAgent(run.getSiteId(), target, id, HistoryAction.AI_DRAFT, run.getId(),
                null, jsonMapper.writeValueAsString(after)));
    }

    // ------------------------------------------------------------------ 변환 도우미

    static PermitType permitTypeOf(String formType) {
        if (formType == null) {
            throw new DraftInvalidException("허가서 초안에 종류가 없습니다.");
        }
        return switch (formType) {
            case "HOT_WORK_PERMIT" -> PermitType.HOT;
            case "CONFINED_SPACE_PERMIT" -> PermitType.CONFINED;
            case "HEIGHT_WORK_PERMIT" -> PermitType.HEIGHT;
            default -> throw new DraftInvalidException("모르는 허가서 종류: " + formType);
        };
    }

    /** 허가서가 있으면 첫 허가서 종류, 없으면 분류 결과, 그것도 없으면 일반 */
    static WorkType workTypeOf(List<DraftPayload.Permit> permits, DraftPayload.Classification c) {
        if (!isEmpty(permits)) {
            return permitTypeOf(permits.get(0).formType()).toWorkType();
        }
        if (c != null && c.workTypes() != null) {
            for (String t : c.workTypes()) {
                switch (t == null ? "" : t) {
                    case "화기": return WorkType.HOT;
                    case "밀폐공간": return WorkType.CONFINED;
                    case "고소": return WorkType.HEIGHT;
                    default: break;
                }
            }
        }
        return WorkType.GENERAL;
    }

    private static String watcherRoleOf(PermitType type) {
        return switch (type) {
            case HOT -> "FIRE_WATCHER";
            case CONFINED -> "ATTENDANT";
            case HEIGHT -> null;
        };
    }

    private static String assignee(DraftPayload.Permit p, String role) {
        if (role == null || p.assignments() == null) {
            return null;
        }
        return p.assignments().stream()
                .filter(a -> role.equals(a.role()) && !isBlank(a.assignee()) && a.assignee().trim().length() <= MAX_NAME)
                .map(a -> a.assignee().trim()).findFirst().orElse(null);
    }

    /** 날짜 + HH:MM. 시각이 없으면 null, 종료가 시작보다 이르면 종료만 비우고 사유를 남긴다 */
    private static LocalDateTime[] range(LocalDate date, DraftPayload.Period period, List<String> notes, String what) {
        LocalTime start = period == null ? null : parseTime(period.start());
        LocalTime end = period == null ? null : parseTime(period.end());
        LocalDateTime from = start == null ? null : date.atTime(start);
        LocalDateTime to = end == null ? null : date.atTime(end);
        if (from != null && to != null && !to.isAfter(from)) {
            notes.add(what + ": 종료 시각(" + period.end() + ")이 시작 시각(" + period.start() + ")보다 이르지 않아 종료 시각을 비웠습니다.");
            to = null;
        }
        return new LocalDateTime[]{from, to};
    }

    private static String describe(String location, DraftPayload.Slots slots) {
        StringBuilder sb = new StringBuilder();
        if (!isBlank(location)) {
            sb.append("장소: ").append(location.trim());
        }
        if (slots != null && !isEmpty(slots.utterances())) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("요청: ").append(String.join(" / ", slots.utterances()));
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static LocalDate parseDate(String s) {
        if (isBlank(s)) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalTime parseTime(String s) {
        if (isBlank(s)) return null;
        try {
            return LocalTime.parse(s.trim().length() == 4 ? "0" + s.trim() : s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static DraftPayload.Period firstPeriod(DraftPayload.Period... periods) {
        for (DraftPayload.Period p : periods) {
            if (p != null && (!isBlank(p.start()) || !isBlank(p.end()))) return p;
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (!isBlank(v)) return v.trim();
        }
        return null;
    }

    private static Integer firstPositive(Integer... values) {
        for (Integer v : values) {
            if (v != null && v >= 1) return v;
        }
        return null;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    /** 초안 값이 업무 규칙에 맞지 않음 → 실행을 AI_BAD_RESPONSE 로 실패 처리 (아무것도 저장하지 않음) */
    public static class DraftInvalidException extends RuntimeException {
        public DraftInvalidException(String message) {
            super(message);
        }
    }
}
