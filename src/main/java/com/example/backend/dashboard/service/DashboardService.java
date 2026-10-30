package com.example.backend.dashboard.service;

import com.example.backend.approval.dto.ApprovalSummary;
import com.example.backend.approval.service.ApprovalService;
import com.example.backend.attachment.entity.AttachmentKind;
import com.example.backend.attachment.entity.AttachmentOwner;
import com.example.backend.attachment.repository.AttachmentRepository;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.corrective.entity.ActionStatus;
import com.example.backend.corrective.entity.CorrectiveAction;
import com.example.backend.corrective.repository.CorrectiveActionRepository;
import com.example.backend.dailylog.entity.DailySafetyLog;
import com.example.backend.dailylog.repository.DailySafetyLogRepository;
import com.example.backend.dashboard.dto.DashboardResponse;
import com.example.backend.dashboard.dto.DashboardResponse.Entry;
import com.example.backend.dashboard.dto.DashboardSummary;
import com.example.backend.dashboard.dto.IncompleteType;
import com.example.backend.inspection.entity.Inspection;
import com.example.backend.inspection.entity.InspectionStatus;
import com.example.backend.inspection.repository.InspectionRepository;
import com.example.backend.permit.entity.MeasurementItem;
import com.example.backend.permit.entity.Permit;
import com.example.backend.permit.entity.PermitMeasurement;
import com.example.backend.permit.entity.PermitStatus;
import com.example.backend.permit.entity.PermitType;
import com.example.backend.permit.repository.PermitMeasurementRepository;
import com.example.backend.permit.repository.PermitRepository;
import com.example.backend.rule.entity.Rule;
import com.example.backend.rule.repository.RuleRepository;
import com.example.backend.signature.entity.SignaturePurpose;
import com.example.backend.signature.entity.SignatureTarget;
import com.example.backend.signature.repository.SignatureRepository;
import com.example.backend.site.entity.Site;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.tbm.entity.TbmRecord;
import com.example.backend.tbm.entity.TbmStatus;
import com.example.backend.tbm.entity.TbmWork;
import com.example.backend.tbm.repository.TbmAttendeeRepository;
import com.example.backend.tbm.repository.TbmRecordRepository;
import com.example.backend.tbm.repository.TbmWorkRepository;
import com.example.backend.work.entity.Work;
import com.example.backend.work.entity.WorkStatus;
import com.example.backend.work.repository.WorkRepository;
import com.example.backend.worker.entity.Team;
import com.example.backend.worker.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 대시보드 미완료 항목 (계획서 7-5장, D 확정 문서 4장).
 * 필수 값이 없으면 승인 요청이 막히므로 "누락된 채 승인된 문서"는 없다. 여기서는 **마감 전에 아직 끝나지 않은 것**을 센다.
 * 기준 날짜는 작업일, 기간은 양 끝 포함, 취소된 작업은 빼고 BLOCKED 는 포함.
 */
@Service
public class DashboardService {

    static final String PRE_WORK = "PRE_WORK";
    static final String NO_TEAM = "팀 미지정";
    private static final Set<PermitStatus> PERMIT_WRITING = EnumSet.of(PermitStatus.DRAFT, PermitStatus.CHECKING,
            PermitStatus.BLOCKED);
    private static final Set<TbmStatus> TBM_HELD = EnumSet.of(TbmStatus.DONE, TbmStatus.APPROVED);
    private static final List<ActionStatus> ACTION_OPEN = List.of(ActionStatus.OPEN, ActionStatus.IN_PROGRESS);
    private static final String MEASUREMENT_PREFIX = "measurement.";

    private final SiteRepository siteRepository;
    private final WorkRepository workRepository;
    private final PermitRepository permitRepository;
    private final PermitMeasurementRepository measurementRepository;
    private final RuleRepository ruleRepository;
    private final TbmRecordRepository tbmRepository;
    private final TbmWorkRepository tbmWorkRepository;
    private final TbmAttendeeRepository attendeeRepository;
    private final DailySafetyLogRepository dailyLogRepository;
    private final InspectionRepository inspectionRepository;
    private final CorrectiveActionRepository actionRepository;
    private final SignatureRepository signatureRepository;
    private final AttachmentRepository attachmentRepository;
    private final TeamRepository teamRepository;
    private final ApprovalService approvalService;
    private final SiteGuard siteGuard;
    private final Clock clock;
    private final int maxDays;

    public DashboardService(SiteRepository siteRepository, WorkRepository workRepository,
                            PermitRepository permitRepository, PermitMeasurementRepository measurementRepository,
                            RuleRepository ruleRepository, TbmRecordRepository tbmRepository,
                            TbmWorkRepository tbmWorkRepository, TbmAttendeeRepository attendeeRepository,
                            DailySafetyLogRepository dailyLogRepository, InspectionRepository inspectionRepository,
                            CorrectiveActionRepository actionRepository, SignatureRepository signatureRepository,
                            AttachmentRepository attachmentRepository, TeamRepository teamRepository,
                            ApprovalService approvalService, SiteGuard siteGuard, Clock clock,
                            @Value("${app.dashboard.max-days}") int maxDays) {
        this.siteRepository = siteRepository;
        this.workRepository = workRepository;
        this.permitRepository = permitRepository;
        this.measurementRepository = measurementRepository;
        this.ruleRepository = ruleRepository;
        this.tbmRepository = tbmRepository;
        this.tbmWorkRepository = tbmWorkRepository;
        this.attendeeRepository = attendeeRepository;
        this.dailyLogRepository = dailyLogRepository;
        this.inspectionRepository = inspectionRepository;
        this.actionRepository = actionRepository;
        this.signatureRepository = signatureRepository;
        this.attachmentRepository = attachmentRepository;
        this.teamRepository = teamRepository;
        this.approvalService = approvalService;
        this.siteGuard = siteGuard;
        this.clock = clock;
        this.maxDays = maxDays;
    }

    // ------------------------------------------------------------------ API

    /** 그날 현황과 미완료 항목 9종. date 를 비우면 오늘(Asia/Seoul) */
    @Transactional(readOnly = true)
    public DashboardResponse day(Long userId, Long siteId, LocalDate date) {
        siteGuard.requireAccess(userId, siteId);
        Site site = site(siteId);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate day = date != null ? date : now.toLocalDate();
        DayFindings f = findings(site, day, day, now).get(day);

        Map<IncompleteType, List<Entry>> all = new EnumMap<>(f.entries());
        all.put(IncompleteType.CORRECTIVE_OPEN, correctiveEntries(siteId, now.toLocalDate()));
        all.put(IncompleteType.APPROVAL_OVERDUE, approvalEntries(siteId, now));
        List<DashboardResponse.Item> items = Arrays.stream(IncompleteType.values()).map(t -> {
            List<Entry> es = all.getOrDefault(t, List.of());
            return new DashboardResponse.Item(t, t.label(), es.size(), es.stream().anyMatch(Entry::overdue), es);
        }).toList();

        LocalDateTime tbmDue = day.atTime(site.getTbmDeadline());
        LocalDateTime logDue = day.atTime(site.getDailyLogDeadline());
        Map<WorkStatus, Integer> byStatus = new EnumMap<>(WorkStatus.class);
        f.works().forEach(w -> byStatus.merge(w.getStatus(), 1, Integer::sum));
        return new DashboardResponse(siteId, day, now,
                new DashboardResponse.Deadlines(tbmDue, !now.isBefore(tbmDue), logDue, !now.isBefore(logDue),
                        site.getApprovalDueMinutes()),
                new DashboardResponse.Works(f.works().size(), f.blocked(), byStatus),
                teams(f), items);
    }

    /** 기간 날짜별 건수. 비우면 오늘 하루, 최대 yaml app.dashboard.max-days 일 */
    @Transactional(readOnly = true)
    public DashboardSummary summary(Long userId, Long siteId, LocalDate from, LocalDate to) {
        siteGuard.requireAccess(userId, siteId);
        Site site = site(siteId);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalDate start = from != null ? from : (to != null ? to : today);
        LocalDate end = to != null ? to : start;
        if (end.isBefore(start)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "to 는 from 과 같거나 뒤여야 합니다.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > maxDays) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "대시보드 기간은 " + maxDays + "일까지 볼 수 있습니다.");
        }
        Map<LocalDate, DayFindings> byDay = findings(site, start, end, now);
        Map<IncompleteType, Integer> totals = new EnumMap<>(IncompleteType.class);
        List<DashboardSummary.Day> days = new ArrayList<>();
        for (DayFindings f : byDay.values()) {
            Map<IncompleteType, Integer> counts = new EnumMap<>(IncompleteType.class);
            for (IncompleteType t : IncompleteType.values()) {
                if (t.byDate()) {
                    int n = f.entries().getOrDefault(t, List.of()).size();
                    counts.put(t, n);
                    totals.merge(t, n, Integer::sum);
                }
            }
            days.add(new DashboardSummary.Day(f.date(), f.works().size(), f.blocked(), counts));
        }
        List<Entry> actions = correctiveEntries(siteId, today);
        int overdueActions = (int) actions.stream().filter(Entry::overdue).count();
        return new DashboardSummary(siteId, start, end, days, totals,
                new DashboardSummary.Current(actions.size(), overdueActions, approvalService.overdue(siteId, now).size()));
    }

    // ------------------------------------------------------------------ 날짜별 미완료 (마감 알림도 같이 씀)

    /** 하루치 결과: 작업(취소 제외)과 날짜 항목별 미완료 */
    public record DayFindings(LocalDate date, List<Work> works, Map<IncompleteType, List<Entry>> entries) {
        public int blocked() {
            return (int) works.stream().filter(w -> w.getStatus() == WorkStatus.BLOCKED).count();
        }
    }

    /** 기간의 날짜별 미완료 (모든 날짜 키 포함, 날짜 순). 권한 확인은 부르는 쪽에서 */
    @Transactional(readOnly = true)
    public Map<LocalDate, DayFindings> findings(Site site, LocalDate from, LocalDate to, LocalDateTime now) {
        Long siteId = site.getId();
        Map<LocalDate, List<Work>> worksByDay = new TreeMap<>();
        Map<LocalDate, Map<IncompleteType, List<Entry>>> entries = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            worksByDay.put(d, new ArrayList<>());
            entries.put(d, new EnumMap<>(IncompleteType.class));
        }

        List<Work> works = workRepository.findBySiteIdAndWorkDateBetweenOrderByWorkDateAscIdAsc(siteId, from, to).stream()
                .filter(w -> w.getStatus() != WorkStatus.CANCELED).toList();
        works.forEach(w -> worksByDay.get(w.getWorkDate()).add(w));
        Map<Long, Work> workById = works.stream().collect(Collectors.toMap(Work::getId, Function.identity()));

        if (!works.isEmpty()) {
            measurementMissing(workById, now, entries);
            tbmMissing(works, site, now, entries);
        }
        dailyLogMissing(site, from, to, worksByDay, now, entries);
        inspections(siteId, from, to, entries);
        tbmSignaturesAndPhotos(siteId, from, to, entries);

        Map<LocalDate, DayFindings> out = new LinkedHashMap<>();
        worksByDay.forEach((d, ws) -> out.put(d, new DayFindings(d, ws, entries.get(d))));
        return out;
    }

    /** 화기·밀폐 허가가 작성 중(DRAFT·CHECKING·BLOCKED)이고 작업 전 측정값이 규칙 측정 항목보다 적음. 작업 시작이 지나면 지연 */
    private void measurementMissing(Map<Long, Work> workById, LocalDateTime now,
                                    Map<LocalDate, Map<IncompleteType, List<Entry>>> entries) {
        Map<String, Set<MeasurementItem>> required = requiredMeasurements();
        if (required.isEmpty()) {
            return;
        }
        List<Permit> writing = latestPermits(permitRepository.findByWorkIdIn(workById.keySet())).stream()
                .filter(p -> PERMIT_WRITING.contains(p.getStatus()) && required.containsKey(p.getPermitType().name()))
                .toList();
        if (writing.isEmpty()) {
            return;
        }
        Map<Long, Set<MeasurementItem>> measured = new HashMap<>();
        for (PermitMeasurement m : measurementRepository.findByPermitIdInAndPhase(
                writing.stream().map(Permit::getId).toList(), PRE_WORK)) {
            measured.computeIfAbsent(m.getPermitId(), k -> EnumSet.noneOf(MeasurementItem.class)).add(m.getItem());
        }
        for (Permit p : writing) {
            Set<MeasurementItem> missing = EnumSet.copyOf(required.get(p.getPermitType().name()));
            missing.removeAll(measured.getOrDefault(p.getId(), Set.of()));
            if (missing.isEmpty()) {
                continue;
            }
            Work w = workById.get(p.getWorkId());
            boolean late = w.getStartAt() != null && !now.isBefore(w.getStartAt());
            add(entries, w.getWorkDate(), IncompleteType.MEASUREMENT_MISSING, new Entry("PERMIT", p.getId(),
                    p.getPermitNo() + " " + permitLabel(p.getPermitType()), w.getId(),
                    missing.stream().map(Enum::name).collect(Collectors.joining(", ")), w.getStartAt(), late));
        }
    }

    /** 허가 종류별 작업 전 측정 항목 = 켜진 MEASUREMENT 규칙의 measurement.{항목} (규칙이 바뀌면 같이 바뀜) */
    Map<String, Set<MeasurementItem>> requiredMeasurements() {
        List<String> types = Arrays.stream(PermitType.values()).map(Enum::name).toList();
        Map<String, Set<MeasurementItem>> out = new HashMap<>();
        for (Rule r : ruleRepository.findByEnabledTrueAndWorkTypeInOrderByRuleCodeAsc(types)) {
            if (!"MEASUREMENT".equals(r.getCategory()) || r.getTarget() == null
                    || !r.getTarget().startsWith(MEASUREMENT_PREFIX)) {
                continue;
            }
            try {
                MeasurementItem item = MeasurementItem.valueOf(r.getTarget().substring(MEASUREMENT_PREFIX.length()));
                out.computeIfAbsent(r.getWorkType(), k -> EnumSet.noneOf(MeasurementItem.class)).add(item);
            } catch (IllegalArgumentException e) {
                // 측정 항목 목록에 없는 규칙 대상은 집계하지 않는다
            }
        }
        return out;
    }

    /** 작업일에 그 작업과 연결된 TBM 이 없음. 현장 tbm_deadline 이후 지연 */
    private void tbmMissing(List<Work> works, Site site, LocalDateTime now,
                            Map<LocalDate, Map<IncompleteType, List<Entry>>> entries) {
        Set<Long> linked = new HashSet<>();
        Map<Long, LocalDate> dateOf = works.stream().collect(Collectors.toMap(Work::getId, Work::getWorkDate));
        for (TbmWork link : tbmWorkRepository.findByWorkIdIn(dateOf.keySet())) {
            if (link.getTbmDate().equals(dateOf.get(link.getWorkId()))) {
                linked.add(link.getWorkId());
            }
        }
        for (Work w : works) {
            if (linked.contains(w.getId())) {
                continue;
            }
            LocalDateTime due = w.getWorkDate().atTime(site.getTbmDeadline());
            add(entries, w.getWorkDate(), IncompleteType.TBM_MISSING,
                    new Entry("WORK", w.getId(), w.getTitle(), w.getId(), null, due, !now.isBefore(due)));
        }
    }

    /** 작업이 있는 날 안전일지가 없음. 현장 daily_log_deadline 이후 지연 */
    private void dailyLogMissing(Site site, LocalDate from, LocalDate to, Map<LocalDate, List<Work>> worksByDay,
                                 LocalDateTime now, Map<LocalDate, Map<IncompleteType, List<Entry>>> entries) {
        Set<LocalDate> written = dailyLogRepository.findBySiteIdAndLogDateBetweenOrderByLogDateDesc(site.getId(), from, to)
                .stream().map(DailySafetyLog::getLogDate).collect(Collectors.toSet());
        worksByDay.forEach((d, ws) -> {
            if (ws.isEmpty() || written.contains(d)) {
                return;
            }
            LocalDateTime due = d.atTime(site.getDailyLogDeadline());
            add(entries, d, IncompleteType.DAILY_LOG_MISSING,
                    new Entry("DAILY_LOG", null, d + " 안전일지", null, null, due, !now.isBefore(due)));
        });
    }

    /** 점검표: 작성 중과 승인 대기를 따로 */
    private void inspections(Long siteId, LocalDate from, LocalDate to,
                             Map<LocalDate, Map<IncompleteType, List<Entry>>> entries) {
        for (Inspection i : inspectionRepository.findBySiteIdAndInspectDateBetweenOrderByInspectDateAscIdAsc(siteId, from, to)) {
            IncompleteType t = i.getStatus() == InspectionStatus.DRAFT ? IncompleteType.INSPECTION_DRAFT
                    : i.getStatus() == InspectionStatus.PENDING ? IncompleteType.INSPECTION_PENDING : null;
            if (t != null) {
                add(entries, i.getInspectDate(), t, new Entry("INSPECTION", i.getId(), "점검표 " + i.getTemplateCode(),
                        i.getWorkId(), i.getTargetType().name(), null, false));
            }
        }
    }

    /** 실시 완료된 TBM(DONE·APPROVED): 참석자 서명이 참석자 수보다 적음, 사진 없음 (1개월 범위는 TBM 만) */
    private void tbmSignaturesAndPhotos(Long siteId, LocalDate from, LocalDate to,
                                        Map<LocalDate, Map<IncompleteType, List<Entry>>> entries) {
        for (TbmRecord t : tbmRepository.findBySiteIdAndTbmDateBetweenOrderByTbmDateAscIdAsc(siteId, from, to)) {
            if (!TBM_HELD.contains(t.getStatus())) {
                continue;
            }
            String title = t.getTbmDate() + " TBM";
            int need = t.getAttendeeCount() != null ? t.getAttendeeCount() : attendeeRepository.countByTbmId(t.getId());
            long signed = signatureRepository.countByTargetTypeAndTargetIdAndPurpose(SignatureTarget.TBM, t.getId(),
                    SignaturePurpose.ATTENDEE);
            if (need > 0 && signed < need) {
                add(entries, t.getTbmDate(), IncompleteType.SIGNATURE_MISSING,
                        new Entry("TBM", t.getId(), title, null, "서명 " + signed + "/" + need, null, false));
            }
            if (!attachmentRepository.existsByOwnerTypeAndOwnerIdAndKind(AttachmentOwner.TBM, t.getId(), AttachmentKind.PHOTO)) {
                add(entries, t.getTbmDate(), IncompleteType.PHOTO_MISSING,
                        new Entry("TBM", t.getId(), title, null, null, null, false));
            }
        }
    }

    // ------------------------------------------------------------------ 지금 기준 현황

    /** 끝나지 않은 개선조치. 기한이 오늘보다 앞이면 지연 */
    List<Entry> correctiveEntries(Long siteId, LocalDate today) {
        return actionRepository.findBySiteIdAndStatusInOrderByDueDateAscIdAsc(siteId, ACTION_OPEN).stream()
                .map(a -> new Entry("CORRECTIVE_ACTION", a.getId(), a.getTitle(), null,
                        a.getStatus().name() + (a.getDueDate() == null ? "" : " · 기한 " + a.getDueDate()), null,
                        a.getDueDate() != null && a.getDueDate().isBefore(today)))
                .toList();
    }

    /** 개선조치 담당자 (마감 알림 받는 사람) */
    Long assigneeOf(Long actionId) {
        return actionRepository.findById(actionId).map(CorrectiveAction::getAssigneeId).orElse(null);
    }

        private List<Entry> approvalEntries(Long siteId, LocalDateTime now) {
        List<ApprovalSummary> overdue = approvalService.overdue(siteId, now);
        return overdue.stream().map(a -> new Entry("APPROVAL", a.approvalId(), a.title(), null,
                a.currentStepLabel(), a.dueAt(), true)).toList();
    }

    // ------------------------------------------------------------------ 팀별

    private List<DashboardResponse.Team> teams(DayFindings f) {
        Map<Long, List<Work>> byTeam = new LinkedHashMap<>();
        f.works().forEach(w -> byTeam.computeIfAbsent(w.getTeamId(), k -> new ArrayList<>()).add(w));
        Map<Long, String> names = teamRepository.findAllById(byTeam.keySet().stream().filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(Team::getId, Team::getName));
        Map<Long, Integer> incompleteByWork = new HashMap<>();
        f.entries().values().forEach(es -> es.stream().filter(e -> e.workId() != null)
                .forEach(e -> incompleteByWork.merge(e.workId(), 1, Integer::sum)));
        return byTeam.entrySet().stream().map(e -> {
            List<Work> ws = e.getValue();
            int blocked = (int) ws.stream().filter(w -> w.getStatus() == WorkStatus.BLOCKED).count();
            int incomplete = ws.stream().mapToInt(w -> incompleteByWork.getOrDefault(w.getId(), 0)).sum();
            String name = e.getKey() == null ? NO_TEAM : names.getOrDefault(e.getKey(), NO_TEAM);
            return new DashboardResponse.Team(e.getKey(), name, ws.size(), blocked, incomplete);
        }).sorted(Comparator.comparing((DashboardResponse.Team t) -> t.teamId() == null)
                .thenComparing(DashboardResponse.Team::teamName)).toList();
    }

    // ------------------------------------------------------------------ 내부

    private Site site(Long siteId) {
        return siteRepository.findById(siteId).orElseThrow(() -> new BusinessException(ErrorCode.SITE_NOT_FOUND));
    }

    /** 작업·종류별 최신 판 */
    private static List<Permit> latestPermits(List<Permit> permits) {
        return permits.stream()
                .collect(Collectors.toMap(p -> p.getWorkId() + ":" + p.getPermitType(), Function.identity(),
                        (a, b) -> a.getRevision() >= b.getRevision() ? a : b))
                .values().stream().sorted(Comparator.comparing(Permit::getId)).toList();
    }

    private static void add(Map<LocalDate, Map<IncompleteType, List<Entry>>> entries, LocalDate date, IncompleteType type,
                            Entry entry) {
        Map<IncompleteType, List<Entry>> day = entries.get(date);
        if (day != null) {
            day.computeIfAbsent(type, k -> new ArrayList<>()).add(entry);
        }
    }

    private static String permitLabel(PermitType type) {
        return switch (type) {
            case HOT -> "화기작업허가";
            case CONFINED -> "밀폐공간작업허가";
            case HEIGHT -> "고소작업허가";
        };
    }
}
