package com.example.backend.record.service;

import com.example.backend.agent.entity.AgentRun;
import com.example.backend.agent.repository.AgentRunRepository;
import com.example.backend.approval.dto.ApprovalSummary;
import com.example.backend.approval.entity.Approval;
import com.example.backend.approval.entity.ApprovalStatus;
import com.example.backend.approval.entity.ApprovalStep;
import com.example.backend.approval.entity.ApprovalStepStatus;
import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.service.ApprovalService;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.corrective.entity.ActionStatus;
import com.example.backend.corrective.entity.CorrectiveAction;
import com.example.backend.history.entity.AccessAction;
import com.example.backend.history.entity.AccessResource;
import com.example.backend.history.entity.ActorType;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordAccessLog;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordAccessLogRepository;
import com.example.backend.inspection.entity.Inspection;
import com.example.backend.inspection.entity.InspectionItem;
import com.example.backend.inspection.entity.ItemResult;
import com.example.backend.permit.entity.MeasurementItem;
import com.example.backend.permit.entity.Permit;
import com.example.backend.permit.entity.PermitMeasurement;
import com.example.backend.permit.entity.PermitType;
import com.example.backend.permit.repository.PermitMeasurementRepository;
import com.example.backend.permit.repository.PermitRepository;
import com.example.backend.record.dto.SearchRecordsRequest;
import com.example.backend.record.dto.SearchRecordsResult;
import com.example.backend.record.dto.SearchRecordsResult.Evidence;
import com.example.backend.risk.entity.RiskAssessment;
import com.example.backend.risk.entity.RiskStatus;
import com.example.backend.risk.repository.RiskAssessmentRepository;
import com.example.backend.rule.entity.EvaluationResult;
import com.example.backend.rule.entity.EvaluationTarget;
import com.example.backend.rule.entity.Rule;
import com.example.backend.rule.entity.RuleEvaluation;
import com.example.backend.rule.entity.RuleViolation;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.rule.service.MeasurementRequirements;
import com.example.backend.site.entity.WorkZone;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.tbm.entity.TbmWork;
import com.example.backend.user.entity.User;
import com.example.backend.work.entity.Work;
import com.example.backend.work.entity.WorkStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 기록 조회 도구 search_records (D 확정 문서 5장). AI 는 조회 유형과 조건만 고르고 SQL 은 만들지 않는다.
 * <ul>
 *   <li><b>공식 기록만</b>: 측정값은 허가 화면에서 입력한 permit_measurements, 판정은 agent_run_id 가 없는 rule_evaluations</li>
 *   <li>기간은 양 끝 포함, 최대 yaml app.records.max-days 일. 결과는 최대 app.records.max-rows 건 (넘으면 truncated)</li>
 *   <li>작성자·승인자 이름이 나오므로 조회마다 열람 기록(record_access_logs, RECORD_QUERY·QUERY)을 남긴다</li>
 *   <li>실행(agentRunId)은 그 현장 것이어야 하고, 실행한 사람이 지금도 그 현장 권한이 있어야 한다</li>
 * </ul>
 */
@Service
public class RecordSearchService {

    private static final String PRE_WORK = "PRE_WORK";
    private static final Set<ApprovalTarget> REVISABLE = EnumSet.of(ApprovalTarget.RISK, ApprovalTarget.PERMIT);

    private final EntityManager em;
    private final AgentRunRepository runRepository;
    private final PermitRepository permitRepository;
    private final PermitMeasurementRepository measurementRepository;
    private final RiskAssessmentRepository riskRepository;
    private final RecordAccessLogRepository accessLogRepository;
    private final MeasurementRequirements measurementRequirements;
    private final ApprovalService approvalService;
    private final SiteGuard siteGuard;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final int maxDays;
    private final int maxRows;

    public RecordSearchService(EntityManager em, AgentRunRepository runRepository, PermitRepository permitRepository,
                               PermitMeasurementRepository measurementRepository, RiskAssessmentRepository riskRepository,
                               RecordAccessLogRepository accessLogRepository,
                               MeasurementRequirements measurementRequirements, ApprovalService approvalService,
                               SiteGuard siteGuard, JsonMapper jsonMapper, Clock clock,
                               @Value("${app.records.max-days}") int maxDays,
                               @Value("${app.records.max-rows}") int maxRows) {
        this.em = em;
        this.runRepository = runRepository;
        this.permitRepository = permitRepository;
        this.measurementRepository = measurementRepository;
        this.riskRepository = riskRepository;
        this.accessLogRepository = accessLogRepository;
        this.measurementRequirements = measurementRequirements;
        this.approvalService = approvalService;
        this.siteGuard = siteGuard;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.maxDays = maxDays;
        this.maxRows = maxRows;
    }

    /** 조회 + 열람 기록 */
    @Transactional
    public SearchRecordsResult search(SearchRecordsRequest req) {
        AgentRun run = runRepository.findById(req.agentRunId())
                .filter(r -> r.getSiteId().equals(req.siteId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "agentRunId 에 해당하는 이 현장의 실행이 없습니다."));
        siteGuard.requireAccess(run.getUserId(), req.siteId());

        RecordQueryType type = RecordQueryType.of(req.queryType())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "모르는 조회 유형입니다: " + req.queryType()));
        Params p = new Params(req.siteId(), type, req.params() == null ? new HashMap<>() : req.params());

        Out out = switch (type) {
            case MISSING_MEASUREMENTS -> missingMeasurements(p);
            case PERMIT_APPROVAL_STATISTICS -> permitApprovalStatistics(p);
            case PENDING_APPROVALS -> pendingApprovals(p);
            case REJECTED_REVISIONS -> rejectedRevisions(p);
            case OPEN_CORRECTIVE_ACTIONS -> openCorrectiveActions(p);
            case FAILED_INSPECTIONS -> failedInspections(p);
            case MISSING_TBM -> missingTbm(p);
            case RISK_ASSESSMENT_RATE -> riskAssessmentRate(p);
            case APPROVER_STATISTICS -> approverStatistics(p);
            case RECORD_CHANGE_HISTORY -> recordChangeHistory(p);
            case BLOCKED_RECORDS -> blockedRecords(p);
        };

        int total = out.total >= 0 ? out.total : out.records.size();
        List<Map<String, Object>> records = out.records.size() > maxRows ? out.records.subList(0, maxRows) : out.records;
        boolean truncated = total > records.size();
        Map<String, Object> summary = out.summary != null ? out.summary : new LinkedHashMap<>(Map.of("count", total));
        List<Evidence> evidence = out.evidence.stream().distinct().limit(maxRows).toList();

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("queryType", type.code());
        detail.put("conditions", p.conditions);
        detail.put("total", total);
        accessLogRepository.save(new RecordAccessLog(run.getUserId(), req.siteId(), AccessResource.RECORD_QUERY, run.getId(),
                AccessAction.QUERY, jsonMapper.writeValueAsString(detail), null));

        return new SearchRecordsResult(type.code(), p.conditions, total, truncated, summary, new ArrayList<>(records), evidence);
    }

    // ------------------------------------------------------------------ 1. 측정값 없이 승인된 허가

    private Out missingMeasurements(Params p) {
        PermitType type = p.enumOf("permitType", PermitType.class);
        List<Permit> permits = permitsInRange(p, type);
        Map<Long, Approval> approved = latestApprovals(ApprovalTarget.PERMIT, ids(permits, Permit::getId), ApprovalStatus.APPROVED);
        List<Permit> targets = permits.stream().filter(x -> approved.containsKey(x.getId())).toList();
        Map<String, Set<MeasurementItem>> required = measurementRequirements.byPermitType();
        Map<Long, Set<MeasurementItem>> measured = new HashMap<>();
        if (!targets.isEmpty()) {
            for (PermitMeasurement m : measurementRepository.findByPermitIdInAndPhase(ids(targets, Permit::getId), PRE_WORK)) {
                measured.computeIfAbsent(m.getPermitId(), k -> EnumSet.noneOf(MeasurementItem.class)).add(m.getItem());
            }
        }
        Map<Long, Work> works = works(targets.stream().map(Permit::getWorkId).toList());
        Map<Long, String> zones = zoneNames(works.values());
        Set<Long> userIds = new HashSet<>();
        targets.forEach(x -> userIds.add(x.getWriterId()));
        approved.values().forEach(a -> userIds.add(a.getApproverId()));
        Map<Long, String> names = names(userIds);

        Out out = new Out();
        for (Permit x : targets) {
            Set<MeasurementItem> need = required.get(x.getPermitType().name());
            if (need == null) {
                continue;
            }
            Set<MeasurementItem> missing = EnumSet.copyOf(need);
            missing.removeAll(measured.getOrDefault(x.getId(), Set.of()));
            if (missing.isEmpty()) {
                continue;
            }
            Work w = works.get(x.getWorkId());
            Approval a = approved.get(x.getId());
            out.add(row("permitId", x.getId(), "permitNo", x.getPermitNo(), "permitType", x.getPermitType(),
                    "workTitle", w == null ? null : w.getTitle(), "location", w == null ? null : zones.get(w.getZoneId()),
                    "writer", names.get(x.getWriterId()), "approver", names.get(a.getApproverId()),
                    "approvedAt", a.getDecidedAt(), "missingItems", missing.stream().map(Enum::name).toList()),
                    "PERMIT", x.getId());
        }
        return out;
    }

    // ------------------------------------------------------------------ 2. 허가 승인 통계

    private Out permitApprovalStatistics(Params p) {
        PermitType type = p.enumOf("permitType", PermitType.class);
        List<Permit> permits = permitsInRange(p, type);
        List<Long> ids = ids(permits, Permit::getId);
        List<Approval> approvals = ids.isEmpty() ? List.of() : em.createQuery(
                        "select a from Approval a where a.targetType = :t and a.targetId in :ids", Approval.class)
                .setParameter("t", ApprovalTarget.PERMIT).setParameter("ids", ids).getResultList();
        Set<Long> blocked = officialBlocked(ids);
        Map<Long, PermitType> typeOf = permits.stream().collect(Collectors.toMap(Permit::getId, Permit::getPermitType));

        Map<PermitType, int[]> byType = new EnumMap<>(PermitType.class);   // permits, requested, approved, rejected, canceled, blocked
        permits.forEach(x -> byType.computeIfAbsent(x.getPermitType(), k -> new int[6])[0]++);
        for (Approval a : approvals) {
            int[] c = byType.get(typeOf.get(a.getTargetId()));
            c[1]++;
            switch (a.getStatus()) {
                case APPROVED -> c[2]++;
                case REJECTED -> c[3]++;
                case CANCELED -> c[4]++;
                default -> { }
            }
        }
        blocked.forEach(id -> byType.get(typeOf.get(id))[5]++);

        Out out = new Out();
        int[] sum = new int[6];
        byType.forEach((t, c) -> {
            for (int i = 0; i < 6; i++) sum[i] += c[i];
            out.add(statRow(t.name(), c), null, null);
        });
        out.summary = statRow(null, sum);
        out.summary.remove("permitType");
        return out;
    }

    private Map<String, Object> statRow(String permitType, int[] c) {
        Map<String, Object> m = row("permitType", permitType, "permits", c[0], "requested", c[1], "approved", c[2],
                "rejected", c[3], "canceled", c[4], "blocked", c[5]);
        m.put("approvalRate", percent(c[2], c[2] + c[3]));   // 승인률 = 승인 ÷ (승인 + 반려)
        return m;
    }

    // ------------------------------------------------------------------ 3. 대기 중 결재

    private Out pendingApprovals(Params p) {
        ApprovalTarget target = p.enumOf("targetType", ApprovalTarget.class);
        Integer stepNo = p.intOf("stepNo");
        String jpql = "select a from Approval a where a.siteId = :s and a.status = :st and a.requestedAt >= :f and a.requestedAt < :t"
                + (target != null ? " and a.targetType = :tt" : "") + (stepNo != null ? " and a.currentStep = :step" : "")
                + " order by a.requestedAt asc, a.id asc";
        TypedQuery<Approval> q = em.createQuery(jpql, Approval.class).setParameter("s", p.siteId)
                .setParameter("st", ApprovalStatus.PENDING).setParameter("f", p.fromAt()).setParameter("t", p.toAt());
        if (target != null) q.setParameter("tt", target);
        if (stepNo != null) q.setParameter("step", stepNo);
        Out out = new Out();
        for (ApprovalSummary a : approvalService.describe(q.getResultList())) {
            out.add(row("approvalId", a.approvalId(), "targetType", a.targetType(), "targetId", a.targetId(),
                    "title", a.title(), "writer", a.requestedByName(), "currentStep", a.currentStep(),
                    "currentStepLabel", a.currentStepLabel(), "requestedAt", a.requestedAt(), "dueAt", a.dueAt(),
                    "overdue", a.overdue()), "APPROVAL", a.approvalId());
        }
        return out;
    }

    // ------------------------------------------------------------------ 4. 반려 후 다시 쓴 문서

    private Out rejectedRevisions(Params p) {
        ApprovalTarget target = p.enumOf("targetType", ApprovalTarget.class);
        if (target != null && !REVISABLE.contains(target)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "판 번호가 있는 위험성평가(RISK)·작업허가(PERMIT)만 조회할 수 있습니다.");
        }
        List<Approval> rejected = em.createQuery("select a from Approval a where a.siteId = :s and a.status = :st"
                        + " and a.targetType in :types and a.decidedAt >= :f and a.decidedAt < :t order by a.decidedAt asc, a.id asc",
                        Approval.class)
                .setParameter("s", p.siteId).setParameter("st", ApprovalStatus.REJECTED)
                .setParameter("types", target != null ? List.of(target) : List.copyOf(REVISABLE))
                .setParameter("f", p.fromAt()).setParameter("t", p.toAt()).getResultList();
        Map<Long, String> names = names(rejected.stream().map(Approval::getApproverId).toList());

        Out out = new Out();
        for (Approval a : rejected) {
            Doc prev = doc(a.getTargetType(), a.getTargetId());
            if (prev == null) {
                continue;
            }
            Doc cur = latestRevision(prev);
            Approval curApproval = cur.id.equals(prev.id) ? null : latestApproval(a.getTargetType(), cur.id);
            String curApprover = curApproval != null && curApproval.getStatus() == ApprovalStatus.APPROVED
                    ? names(List.of(curApproval.getApproverId())).get(curApproval.getApproverId()) : null;
            out.add(row("approvalId", a.getId(), "targetType", a.getTargetType(),
                    "previousId", prev.id, "previousRevision", prev.revision,
                    "currentId", cur.id, "currentRevision", cur.revision, "currentStatus", cur.status,
                    "reason", a.getComment(), "rejectedBy", names.get(a.getApproverId()), "rejectedAt", a.getDecidedAt(),
                    "reRequestedAt", curApproval == null ? null : curApproval.getRequestedAt(), "approver", curApprover),
                    a.getTargetType().name(), prev.id);
            if (!cur.id.equals(prev.id)) {
                out.evidence.add(new Evidence(a.getTargetType().name(), cur.id));
            }
        }
        return out;
    }

    private record Doc(Long id, Long workId, PermitType permitType, int revision, String status) {
    }

    private Doc doc(ApprovalTarget type, Long id) {
        if (type == ApprovalTarget.PERMIT) {
            return permitRepository.findById(id).map(x -> new Doc(x.getId(), x.getWorkId(), x.getPermitType(),
                    x.getRevision(), x.getStatus().name())).orElse(null);
        }
        return riskRepository.findById(id).map(x -> new Doc(x.getId(), x.getWorkId(), null, x.getRevision(),
                x.getStatus().name())).orElse(null);
    }

    private Doc latestRevision(Doc prev) {
        if (prev.workId == null) {
            return prev;
        }
        if (prev.permitType != null) {
            return permitRepository.findTopByWorkIdAndPermitTypeOrderByRevisionDesc(prev.workId, prev.permitType)
                    .map(x -> new Doc(x.getId(), x.getWorkId(), x.getPermitType(), x.getRevision(), x.getStatus().name()))
                    .orElse(prev);
        }
        return riskRepository.findTopByWorkIdOrderByRevisionDesc(prev.workId)
                .map(x -> new Doc(x.getId(), x.getWorkId(), null, x.getRevision(), x.getStatus().name())).orElse(prev);
    }

    // ------------------------------------------------------------------ 5. 개선조치 + 조치율

    private Out openCorrectiveActions(Params p) {
        ActionStatus status = p.enumOf("status", ActionStatus.class);
        List<CorrectiveAction> all = em.createQuery("select c from CorrectiveAction c where c.siteId = :s"
                        + " and c.createdAt >= :f and c.createdAt < :t order by c.dueDate asc, c.id asc", CorrectiveAction.class)
                .setParameter("s", p.siteId).setParameter("f", p.fromAt()).setParameter("t", p.toAt()).getResultList();
        LocalDate today = LocalDate.now(clock);
        List<CorrectiveAction> counted = all.stream().filter(c -> c.getStatus() != ActionStatus.CANCELED).toList();
        long done = counted.stream().filter(c -> c.getStatus() == ActionStatus.DONE || c.getStatus() == ActionStatus.VERIFIED).count();
        long open = counted.stream().filter(c -> c.getStatus().isOpen()).count();
        long overdue = counted.stream().filter(c -> c.getStatus().isOpen() && c.getDueDate() != null
                && c.getDueDate().isBefore(today)).count();
        List<CorrectiveAction> shown = all.stream()
                .filter(c -> status != null ? c.getStatus() == status : c.getStatus().isOpen()).toList();
        Map<Long, String> names = names(shown.stream().map(CorrectiveAction::getAssigneeId).toList());

        Out out = new Out();
        for (CorrectiveAction c : shown) {
            out.add(row("actionId", c.getId(), "title", c.getTitle(), "sourceType", c.getSourceType(),
                    "sourceId", c.getSourceId(), "assignee", names.get(c.getAssigneeId()), "dueDate", c.getDueDate(),
                    "status", c.getStatus(), "overdue", c.getStatus().isOpen() && c.getDueDate() != null
                            && c.getDueDate().isBefore(today)), "CORRECTIVE_ACTION", c.getId());
        }
        out.summary = row("count", shown.size(), "total", counted.size(), "completed", (int) done, "open", (int) open,
                "overdue", (int) overdue);
        out.summary.put("completionRate", percent((int) done, counted.size()));   // 조치율 = 완료(DONE·VERIFIED) ÷ 전체(취소 제외)
        return out;
    }

    // ------------------------------------------------------------------ 6. 불량 점검

    private Out failedInspections(Params p) {
        Long zoneId = p.longOf("zoneId");
        String template = p.stringOf("templateCode");
        String jpql = "select i from Inspection i where i.siteId = :s and i.inspectDate >= :f and i.inspectDate <= :t"
                + (zoneId != null ? " and i.zoneId = :z" : "") + (template != null ? " and i.templateCode = :tc" : "")
                + " order by i.inspectDate asc, i.id asc";
        TypedQuery<Inspection> q = em.createQuery(jpql, Inspection.class).setParameter("s", p.siteId)
                .setParameter("f", p.from).setParameter("t", p.to);
        if (zoneId != null) q.setParameter("z", zoneId);
        if (template != null) q.setParameter("tc", template);
        List<Inspection> inspections = q.getResultList();
        List<Long> ids = ids(inspections, Inspection::getId);
        Map<Long, List<InspectionItem>> ng = ids.isEmpty() ? new HashMap<>() : em.createQuery(
                        "select it from InspectionItem it where it.inspectionId in :ids and it.result = :r order by it.seq",
                        InspectionItem.class).setParameter("ids", ids).setParameter("r", ItemResult.NG).getResultList()
                .stream().collect(Collectors.groupingBy(InspectionItem::getInspectionId));
        Map<Long, String> names = names(inspections.stream().map(Inspection::getInspectorId).toList());
        Map<Long, String> zones = zoneNamesById(inspections.stream().map(Inspection::getZoneId).toList());

        Out out = new Out();
        for (Inspection i : inspections) {
            List<InspectionItem> items = ng.get(i.getId());
            if (items == null || items.isEmpty()) {
                continue;
            }
            out.add(row("inspectionId", i.getId(), "workId", i.getWorkId(), "inspectDate", i.getInspectDate(),
                    "templateCode", i.getTemplateCode(), "zone", zones.get(i.getZoneId()),
                    "ngItems", items.stream().map(it -> row("itemCode", it.getItemCode(), "question", it.getQuestion(),
                            "note", it.getNote())).toList(),
                    "inspector", names.get(i.getInspectorId()), "status", i.getStatus()), "INSPECTION", i.getId());
        }
        return out;
    }

    // ------------------------------------------------------------------ 7. TBM 없는 작업

    private Out missingTbm(Params p) {
        List<Work> works = worksInRange(p, p.enumOf("workType", WorkType.class), p.longOf("zoneId"));
        Set<Long> linked = new HashSet<>();
        if (!works.isEmpty()) {
            Map<Long, LocalDate> dateOf = works.stream().collect(Collectors.toMap(Work::getId, Work::getWorkDate));
            em.createQuery("select tw from TbmWork tw where tw.workId in :ids", TbmWork.class)
                    .setParameter("ids", dateOf.keySet()).getResultList().stream()
                    .filter(tw -> tw.getTbmDate().equals(dateOf.get(tw.getWorkId()))).forEach(tw -> linked.add(tw.getWorkId()));
        }
        List<Work> missing = works.stream().filter(w -> !linked.contains(w.getId())).toList();
        Map<Long, String> zones = zoneNames(missing);
        Map<Long, String> names = names(missing.stream().map(Work::getWriterId).toList());
        Out out = new Out();
        for (Work w : missing) {
            out.add(row("workId", w.getId(), "title", w.getTitle(), "workDate", w.getWorkDate(),
                    "location", zones.get(w.getZoneId()), "workType", w.getWorkType(), "writer", names.get(w.getWriterId())),
                    "WORK", w.getId());
        }
        return out;
    }

    // ------------------------------------------------------------------ 8. 위험성평가 실시율

    private Out riskAssessmentRate(Params p) {
        List<Work> works = worksInRange(p, p.enumOf("workType", WorkType.class), null);
        Map<Long, RiskAssessment> latest = works.isEmpty() ? new HashMap<>() : riskRepository.findByWorkIdIn(ids(works, Work::getId))
                .stream().collect(Collectors.toMap(RiskAssessment::getWorkId, Function.identity(),
                        (a, b) -> a.getRevision() >= b.getRevision() ? a : b));
        Map<WorkType, int[]> byType = new EnumMap<>(WorkType.class);   // works, written, approved
        for (Work w : works) {
            int[] c = byType.computeIfAbsent(w.getWorkType(), k -> new int[3]);
            c[0]++;
            RiskAssessment r = latest.get(w.getId());
            if (r != null) {
                c[1]++;
                if (r.getStatus() == RiskStatus.APPROVED) c[2]++;
            }
        }
        Out out = new Out();
        int[] sum = new int[3];
        byType.forEach((t, c) -> {
            for (int i = 0; i < 3; i++) sum[i] += c[i];
            out.add(rateRow(t.name(), c), null, null);
        });
        out.summary = rateRow(null, sum);
        out.summary.remove("workType");
        return out;
    }

    /** 실시율 = 평가 작성 ÷ 전체 작업, 승인율 = 승인 ÷ 전체 작업 (취소 작업 제외) */
    private Map<String, Object> rateRow(String workType, int[] c) {
        Map<String, Object> m = row("workType", workType, "works", c[0], "written", c[1], "approved", c[2],
                "missing", c[0] - c[1]);
        m.put("rate", percent(c[1], c[0]));
        m.put("approvedRate", percent(c[2], c[0]));
        return m;
    }

    // ------------------------------------------------------------------ 9. 결재자별 통계

    private Out approverStatistics(Params p) {
        Long approverId = p.longOf("approverId");
        String jpql = "select s from ApprovalStep s, Approval a where s.approvalId = a.id and a.siteId = :site"
                + " and s.status in :st and s.decidedAt >= :f and s.decidedAt < :t"
                + (approverId != null ? " and s.decidedBy = :u" : "") + " order by s.decidedAt asc, s.id asc";
        TypedQuery<ApprovalStep> q = em.createQuery(jpql, ApprovalStep.class).setParameter("site", p.siteId)
                .setParameter("st", List.of(ApprovalStepStatus.APPROVED, ApprovalStepStatus.REJECTED))
                .setParameter("f", p.fromAt()).setParameter("t", p.toAt());
        if (approverId != null) q.setParameter("u", approverId);
        List<ApprovalStep> steps = q.getResultList();
        Set<Long> approvalIds = steps.stream().map(ApprovalStep::getApprovalId).collect(Collectors.toSet());
        Map<Long, Approval> approvals = approvalIds.isEmpty() ? new HashMap<>() : em.createQuery(
                        "select a from Approval a where a.id in :ids", Approval.class).setParameter("ids", approvalIds)
                .getResultList().stream().collect(Collectors.toMap(Approval::getId, Function.identity()));
        Map<String, LocalDateTime> prevDecided = new HashMap<>();   // "결재:단계" → 앞 단계 결정 시각
        if (!approvalIds.isEmpty()) {
            em.createQuery("select s from ApprovalStep s where s.approvalId in :ids", ApprovalStep.class)
                    .setParameter("ids", approvalIds).getResultList()
                    .forEach(s -> prevDecided.put(s.getApprovalId() + ":" + (s.getStepNo() + 1), s.getDecidedAt()));
        }

        Map<Long, long[]> by = new LinkedHashMap<>();   // decided, approved, rejected, self, minutesSum, overdue
        for (ApprovalStep s : steps) {
            Approval a = approvals.get(s.getApprovalId());
            long[] c = by.computeIfAbsent(s.getDecidedBy(), k -> new long[6]);
            c[0]++;
            if (s.getStatus() == ApprovalStepStatus.APPROVED) c[1]++; else c[2]++;
            if (s.getDecidedBy().equals(a.getRequestedBy())) c[3]++;
            LocalDateTime opened = s.getStepNo() == 1 ? a.getRequestedAt()
                    : prevDecided.getOrDefault(s.getApprovalId() + ":" + s.getStepNo(), a.getRequestedAt());
            c[4] += Math.max(0, Duration.between(opened, s.getDecidedAt()).toMinutes());
            if (a.getDueAt() != null && s.getDecidedAt().isAfter(a.getDueAt())) c[5]++;
        }
        Map<Long, String> names = names(by.keySet());
        Out out = new Out();
        long[] sum = new long[6];
        by.forEach((userId, c) -> {
            for (int i = 0; i < 6; i++) sum[i] += c[i];
            Map<String, Object> m = approverRow(c);
            m.put("approverId", userId);
            m.put("approver", names.get(userId));
            out.add(m, null, null);
        });
        out.summary = approverRow(sum);
        return out;
    }

    private Map<String, Object> approverRow(long[] c) {
        return row("decided", c[0], "approved", c[1], "rejected", c[2], "selfApproved", c[3],
                "avgMinutes", c[0] == 0 ? null : Math.round((double) c[4] / c[0]), "overdue", c[5]);
    }

    // ------------------------------------------------------------------ 10. 기록 변경 이력

    private Out recordChangeHistory(Params p) {
        HistoryTarget recordType = p.enumOf("recordType", HistoryTarget.class);
        Long recordId = p.longOf("recordId");
        Long userId = p.longOf("userId");
        if (recordId != null && recordType == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "recordId 는 recordType 과 함께 보내 주세요.");
        }
        String where = " from RecordHistory h where h.siteId = :s and h.createdAt >= :f and h.createdAt < :t"
                + (recordType != null ? " and h.targetType = :rt" : "") + (recordId != null ? " and h.targetId = :rid" : "")
                + (userId != null ? " and h.actorType = :at and h.actorId = :u" : "");
        TypedQuery<Long> countQ = em.createQuery("select count(h)" + where, Long.class);
        TypedQuery<RecordHistory> q = em.createQuery("select h" + where + " order by h.createdAt desc, h.id desc", RecordHistory.class);
        for (TypedQuery<?> x : List.of(countQ, q)) {
            x.setParameter("s", p.siteId).setParameter("f", p.fromAt()).setParameter("t", p.toAt());
            if (recordType != null) x.setParameter("rt", recordType);
            if (recordId != null) x.setParameter("rid", recordId);
            if (userId != null) x.setParameter("at", ActorType.USER).setParameter("u", userId);
        }
        List<RecordHistory> rows = q.setMaxResults(maxRows).getResultList();
        Map<Long, String> names = names(rows.stream().filter(h -> h.getActorType() == ActorType.USER)
                .map(RecordHistory::getActorId).toList());
        Out out = new Out();
        out.total = countQ.getSingleResult().intValue();
        for (RecordHistory h : rows) {
            out.add(row("historyId", h.getId(), "recordType", h.getTargetType(), "recordId", h.getTargetId(),
                    "action", h.getAction(), "actorType", h.getActorType(), "actorId", h.getActorId(),
                    "actor", h.getActorType() == ActorType.USER ? names.get(h.getActorId()) : null,
                    "at", h.getCreatedAt(), "before", json(h.getBeforeJson()), "after", json(h.getAfterJson())),
                    h.getTargetType().name(), h.getTargetId());
        }
        return out;
    }

    // ------------------------------------------------------------------ 11. 규칙 차단 기록

    private Out blockedRecords(Params p) {
        PermitType type = p.enumOf("permitType", PermitType.class);
        List<RuleEvaluation> evals = em.createQuery("select e from RuleEvaluation e where e.siteId = :s and e.targetType = :tt"
                        + " and e.result = :r and e.agentRunId is null and e.evaluatedAt >= :f and e.evaluatedAt < :t"
                        + " order by e.evaluatedAt asc, e.id asc", RuleEvaluation.class)
                .setParameter("s", p.siteId).setParameter("tt", EvaluationTarget.PERMIT).setParameter("r", EvaluationResult.BLOCK)
                .setParameter("f", p.fromAt()).setParameter("t", p.toAt()).getResultList();
        Map<Long, Permit> permits = evals.isEmpty() ? new HashMap<>() : permitRepository.findAllById(
                evals.stream().map(RuleEvaluation::getTargetId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Permit::getId, Function.identity()));
        List<RuleEvaluation> shown = evals.stream().filter(e -> permits.containsKey(e.getTargetId())
                && (type == null || permits.get(e.getTargetId()).getPermitType() == type)).toList();
        List<Long> evalIds = ids(shown, RuleEvaluation::getId);
        List<RuleViolation> violations = evalIds.isEmpty() ? List.of() : em.createQuery(
                        "select v from RuleViolation v where v.evaluationId in :ids order by v.id", RuleViolation.class)
                .setParameter("ids", evalIds).getResultList();
        Map<Long, String> ruleCodes = violations.isEmpty() ? new HashMap<>() : em.createQuery(
                        "select r from Rule r where r.id in :ids", Rule.class)
                .setParameter("ids", violations.stream().map(RuleViolation::getRuleId).collect(Collectors.toSet()))
                .getResultList().stream().collect(Collectors.toMap(Rule::getId, Rule::getRuleCode));
        Map<Long, List<RuleViolation>> byEval = violations.stream().collect(Collectors.groupingBy(RuleViolation::getEvaluationId));

        Out out = new Out();
        for (RuleEvaluation e : shown) {
            Permit x = permits.get(e.getTargetId());
            out.add(row("permitId", x.getId(), "permitNo", x.getPermitNo(), "permitType", x.getPermitType(),
                    "evaluationId", e.getId(), "blockedAt", e.getEvaluatedAt(),
                    "violations", byEval.getOrDefault(e.getId(), List.of()).stream().map(v -> row(
                            "ruleCode", ruleCodes.get(v.getRuleId()), "actual", v.getActualValue(), "expected", v.getExpected(),
                            "severity", v.getSeverity(), "message", v.getMessage(), "legalBasis", v.getLegalBasis())).toList(),
                    "currentStatus", x.getStatus()), "PERMIT", x.getId());
        }
        return out;
    }

    // ------------------------------------------------------------------ 공통 조회

    private List<Permit> permitsInRange(Params p, PermitType type) {
        TypedQuery<Permit> q = em.createQuery("select x from Permit x where x.siteId = :s and x.validFrom >= :f and x.validFrom < :t"
                + (type != null ? " and x.permitType = :pt" : "") + " order by x.validFrom asc, x.id asc", Permit.class)
                .setParameter("s", p.siteId).setParameter("f", p.fromAt()).setParameter("t", p.toAt());
        if (type != null) q.setParameter("pt", type);
        return q.getResultList();
    }

    /** 기간 작업 (취소 제외) */
    private List<Work> worksInRange(Params p, WorkType type, Long zoneId) {
        TypedQuery<Work> q = em.createQuery("select w from Work w where w.siteId = :s and w.workDate >= :f and w.workDate <= :t"
                + " and w.status <> :canceled" + (type != null ? " and w.workType = :wt" : "")
                + (zoneId != null ? " and w.zoneId = :z" : "") + " order by w.workDate asc, w.id asc", Work.class)
                .setParameter("s", p.siteId).setParameter("f", p.from).setParameter("t", p.to)
                .setParameter("canceled", WorkStatus.CANCELED);
        if (type != null) q.setParameter("wt", type);
        if (zoneId != null) q.setParameter("z", zoneId);
        return q.getResultList();
    }

    /** 문서별 가장 최근 결재 (상태 지정) */
    private Map<Long, Approval> latestApprovals(ApprovalTarget type, List<Long> targetIds, ApprovalStatus status) {
        if (targetIds.isEmpty()) {
            return new HashMap<>();
        }
        return em.createQuery("select a from Approval a where a.targetType = :t and a.targetId in :ids and a.status = :st",
                        Approval.class).setParameter("t", type).setParameter("ids", targetIds).setParameter("st", status)
                .getResultList().stream().collect(Collectors.toMap(Approval::getTargetId, Function.identity(),
                        (a, b) -> a.getRequestedAt().isAfter(b.getRequestedAt()) ? a : b));
    }

    private Approval latestApproval(ApprovalTarget type, Long targetId) {
        return em.createQuery("select a from Approval a where a.targetType = :t and a.targetId = :id"
                        + " order by a.requestedAt desc, a.id desc", Approval.class)
                .setParameter("t", type).setParameter("id", targetId).setMaxResults(1)
                .getResultList().stream().findFirst().orElse(null);
    }

    /** 공식 판정(agent_run_id 없음)으로 한 번이라도 BLOCK 된 허가 */
    private Set<Long> officialBlocked(List<Long> permitIds) {
        if (permitIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(em.createQuery("select distinct e.targetId from RuleEvaluation e where e.targetType = :tt"
                        + " and e.result = :r and e.agentRunId is null and e.targetId in :ids", Long.class)
                .setParameter("tt", EvaluationTarget.PERMIT).setParameter("r", EvaluationResult.BLOCK)
                .setParameter("ids", permitIds).getResultList());
    }

    private Map<Long, Work> works(Collection<Long> ids) {
        Set<Long> set = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return set.isEmpty() ? new HashMap<>() : em.createQuery("select w from Work w where w.id in :ids", Work.class)
                .setParameter("ids", set).getResultList().stream().collect(Collectors.toMap(Work::getId, Function.identity()));
    }

    private Map<Long, String> zoneNames(Collection<Work> works) {
        return zoneNamesById(works.stream().map(Work::getZoneId).toList());
    }

    private Map<Long, String> zoneNamesById(Collection<Long> zoneIds) {
        Set<Long> set = zoneIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return set.isEmpty() ? new HashMap<>() : em.createQuery("select z from WorkZone z where z.id in :ids", WorkZone.class)
                .setParameter("ids", set).getResultList().stream().collect(Collectors.toMap(WorkZone::getId, WorkZone::getName));
    }

    private Map<Long, String> names(Collection<Long> userIds) {
        Set<Long> set = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return set.isEmpty() ? new HashMap<>() : em.createQuery("select u from User u where u.id in :ids", User.class)
                .setParameter("ids", set).getResultList().stream()
                .collect(Collectors.toMap(User::getId, u -> u.getName() == null ? u.getEmail() : u.getName()));
    }

    private Object json(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return jsonMapper.readTree(raw);
        } catch (JacksonException e) {
            return raw;
        }
    }

    private static <T> List<Long> ids(Collection<T> items, Function<T, Long> id) {
        return items.stream().map(id).toList();
    }

    /** 백분율, 소수 첫째 자리. 분모가 0 이면 null */
    static BigDecimal percent(int part, int whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    /** 키·값 번갈아. null 값도 넣는다 (화면이 빈칸으로 보여 줌) */
    static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put((String) kv[i], v instanceof Enum<?> e ? e.name() : v);
        }
        return m;
    }

    /** 조회 결과 모으기 */
    private static final class Out {
        final List<Map<String, Object>> records = new ArrayList<>();
        final List<Evidence> evidence = new ArrayList<>();
        Map<String, Object> summary;
        int total = -1;

        void add(Map<String, Object> record, String evidenceType, Long evidenceId) {
            records.add(record);
            if (evidenceType != null && evidenceId != null) {
                evidence.add(new Evidence(evidenceType, evidenceId));
            }
        }
    }

    // ------------------------------------------------------------------ 조건 읽기

    /** 조건 검사: from·to 필수(양 끝 포함, 최대 maxDays), 유형에 없는 조건은 400. conditions 는 실제로 쓴 조건 */
    private final class Params {
        final Long siteId;
        final LocalDate from;
        final LocalDate to;
        final Map<String, Object> raw;
        final Map<String, Object> conditions = new LinkedHashMap<>();

        Params(Long siteId, RecordQueryType type, Map<String, Object> raw) {
            for (String key : raw.keySet()) {
                if (!RecordQueryType.COMMON.contains(key) && !type.params().contains(key)) {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST,
                            type.code() + " 에 없는 조건입니다: " + key + " (받는 조건: from, to, " + String.join(", ", new TreeSet<>(type.params())) + ")");
                }
            }
            this.siteId = siteId;
            this.raw = raw;
            this.from = date("from");
            this.to = date("to");
            if (to.isBefore(from)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "조회 기간이 올바르지 않습니다 (to 가 from 보다 앞).");
            }
            if (ChronoUnit.DAYS.between(from, to) + 1 > maxDays) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "조회 기간은 " + maxDays + "일까지입니다.");
            }
            conditions.put("siteId", siteId);
            conditions.put("from", from.toString());
            conditions.put("to", to.toString());
        }

        LocalDateTime fromAt() {
            return from.atStartOfDay();
        }

        /** 끝 날짜 다음 날 0시 (미포함) */
        LocalDateTime toAt() {
            return to.plusDays(1).atStartOfDay();
        }

        private LocalDate date(String key) {
            Object v = raw.get(key);
            if (v == null || v.toString().isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, key + " 가 필요합니다 (YYYY-MM-DD).");
            }
            try {
                return LocalDate.parse(v.toString());
            } catch (DateTimeParseException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, key + " 는 YYYY-MM-DD 형식이어야 합니다.");
            }
        }

        <E extends Enum<E>> E enumOf(String key, Class<E> type) {
            String s = stringOf(key);
            if (s == null) {
                return null;
            }
            try {
                E e = Enum.valueOf(type, s);
                conditions.put(key, e.name());
                return e;
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, key + " 값이 올바르지 않습니다: " + s
                        + " (가능: " + Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", ")) + ")");
            }
        }

        Long longOf(String key) {
            Object v = raw.get(key);
            if (v == null || v.toString().isBlank()) {
                return null;
            }
            try {
                long n = v instanceof Number num ? num.longValue() : Long.parseLong(v.toString().trim());
                if (n < 1) throw new NumberFormatException();
                conditions.put(key, n);
                return n;
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, key + " 는 1 이상의 정수여야 합니다.");
            }
        }

        Integer intOf(String key) {
            Long n = longOf(key);
            if (n != null && n > 10) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, key + " 는 1~10 입니다.");
            }
            return n == null ? null : n.intValue();
        }

        String stringOf(String key) {
            Object v = raw.get(key);
            if (v == null || v.toString().isBlank()) {
                return null;
            }
            String s = v.toString().trim();
            if (s.length() > 100) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, key + " 가 너무 깁니다.");
            }
            if (!(v instanceof String)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, key + " 는 문자열이어야 합니다.");
            }
            conditions.put(key, s);
            return s;
        }
    }
}
