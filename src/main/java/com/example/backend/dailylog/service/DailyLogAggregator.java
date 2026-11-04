package com.example.backend.dailylog.service;

import com.example.backend.dashboard.dto.DashboardResponse;
import com.example.backend.dashboard.dto.IncompleteType;
import com.example.backend.dashboard.service.DashboardService;
import com.example.backend.inspection.entity.Inspection;
import com.example.backend.inspection.repository.InspectionRepository;
import com.example.backend.inspection.service.InspectionTemplates;
import com.example.backend.nearmiss.entity.NearMiss;
import com.example.backend.nearmiss.repository.NearMissRepository;
import com.example.backend.permit.entity.Permit;
import com.example.backend.permit.entity.PermitStatus;
import com.example.backend.permit.entity.PermitType;
import com.example.backend.permit.repository.PermitRepository;
import com.example.backend.tbm.entity.TbmRecord;
import com.example.backend.tbm.entity.TbmStatus;
import com.example.backend.tbm.repository.TbmRecordRepository;
import com.example.backend.work.entity.Work;
import com.example.backend.work.repository.WorkRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 일일 안전일지 집계 (계획서 7장: 집계는 SQL·코드로 하고 LLM 이 하지 않는다).
 * 미완료·지연 판단은 대시보드와 같은 기준(DashboardService.day)을 그대로 쓴다.
 * 요약 문장은 집계 숫자로 만든 정해진 형식이다 (AI 요약은 이후 숫자 대조를 거쳐 붙인다).
 */
@Component
public class DailyLogAggregator {

    private static final Map<PermitType, String> PERMIT_LABELS = Map.of(
            PermitType.HOT, "화기", PermitType.CONFINED, "밀폐공간", PermitType.HEIGHT, "고소");

    private final DashboardService dashboardService;
    private final WorkRepository workRepository;
    private final PermitRepository permitRepository;
    private final TbmRecordRepository tbmRepository;
    private final InspectionRepository inspectionRepository;
    private final InspectionTemplates templates;
    private final NearMissRepository nearMissRepository;

    public DailyLogAggregator(DashboardService dashboardService, WorkRepository workRepository,
                              PermitRepository permitRepository, TbmRecordRepository tbmRepository,
                              InspectionRepository inspectionRepository, InspectionTemplates templates,
                              NearMissRepository nearMissRepository) {
        this.dashboardService = dashboardService;
        this.workRepository = workRepository;
        this.permitRepository = permitRepository;
        this.tbmRepository = tbmRepository;
        this.inspectionRepository = inspectionRepository;
        this.templates = templates;
        this.nearMissRepository = nearMissRepository;
    }

    /** 근거 기록 한 줄 */
    public record Evidence(String targetType, Long targetId, String title, String detail) {
    }

    /** 집계 결과. stats 는 숫자만, checks 는 대시보드 items 중 건수가 있는 것(안전일지 미작성 제외) */
    public record Snapshot(Map<String, Object> stats, List<DashboardResponse.Item> checks, List<Evidence> evidence,
                           String summary) {
    }

    /** 현장 권한 확인은 DashboardService.day 가 한다 */
    public Snapshot build(Long userId, Long siteId, LocalDate date) {
        DashboardResponse day = dashboardService.day(userId, siteId, date);
        List<Work> works = workRepository.findBySiteIdAndWorkDateBetweenOrderByWorkDateAscIdAsc(siteId, date, date);
        List<Permit> permits = works.isEmpty() ? List.of()
                : permitRepository.findByWorkIdIn(works.stream().map(Work::getId).toList()).stream()
                        .filter(p -> p.getStatus() != PermitStatus.SUPERSEDED)
                        .sorted(Comparator.comparing(Permit::getId)).toList();
        List<TbmRecord> tbms = tbmRepository.findBySiteIdAndTbmDateOrderByIdAsc(siteId, date);
        List<Inspection> inspections = inspectionRepository.findBySiteIdAndInspectDateOrderByIdAsc(siteId, date);
        List<NearMiss> nearMisses = nearMissRepository
                .findBySiteIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAscIdAsc(
                        siteId, date.atStartOfDay(), date.plusDays(1).atStartOfDay());

        Map<IncompleteType, DashboardResponse.Item> items = new LinkedHashMap<>();
        day.items().forEach(i -> items.put(i.type(), i));
        List<DashboardResponse.Item> checks = day.items().stream()
                .filter(i -> i.type() != IncompleteType.DAILY_LOG_MISSING && i.count() > 0).toList();

        Counts c = new Counts(
                day.works().total(), day.works().blocked(),
                permits.size(), count(permits, PermitStatus.APPROVED), count(permits, PermitStatus.PENDING),
                count(permits, PermitStatus.BLOCKED),
                tbms.size(), (int) tbms.stream().filter(t -> t.getStatus() != TbmStatus.DRAFT).count(),
                countOf(items, IncompleteType.TBM_MISSING),
                inspections.size(), countOf(items, IncompleteType.CORRECTIVE_OPEN),
                overdueOf(items, IncompleteType.CORRECTIVE_OPEN), nearMisses.size());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("works", Map.of("total", c.works, "blocked", c.worksBlocked, "byStatus", day.works().byStatus()));
        stats.put("teams", day.teams());
        stats.put("permits", Map.of("total", c.permits, "approved", c.permitsApproved, "pending", c.permitsPending,
                "blocked", c.permitsBlocked, "byStatus", byStatus(permits.stream().map(p -> p.getStatus().name()).toList())));
        stats.put("tbm", Map.of("total", c.tbm, "done", c.tbmDone, "missing", c.tbmMissing));
        stats.put("inspections", Map.of("total", c.inspections,
                "byStatus", byStatus(inspections.stream().map(i -> i.getStatus().name()).toList())));
        stats.put("correctiveActions", Map.of("open", c.correctiveOpen, "overdue", c.correctiveOverdue));
        stats.put("nearMisses", c.nearMisses);
        Map<String, Integer> incomplete = new LinkedHashMap<>();
        day.items().stream().filter(i -> i.type() != IncompleteType.DAILY_LOG_MISSING)
                .forEach(i -> incomplete.put(i.type().name(), i.count()));
        stats.put("incomplete", incomplete);

        List<Evidence> evidence = new ArrayList<>();
        permits.forEach(p -> evidence.add(new Evidence("PERMIT", p.getId(),
                p.getPermitNo() + " " + PERMIT_LABELS.getOrDefault(p.getPermitType(), p.getPermitType().name()) + "작업허가",
                p.getStatus().name())));
        tbms.forEach(t -> evidence.add(new Evidence("TBM", t.getId(), t.getTbmDate() + " TBM", t.getStatus().name())));
        inspections.forEach(i -> evidence.add(new Evidence("INSPECTION", i.getId(),
                templates.find(i.getTemplateCode()).map(InspectionTemplates.Template::name).orElse(i.getTemplateCode()),
                i.getStatus().name())));
        nearMisses.forEach(n -> evidence.add(new Evidence("NEAR_MISS", n.getId(), n.getTitle(), null)));

        return new Snapshot(stats, checks, evidence, summarize(date, c, checks));
    }

    /** 요약에 쓰는 숫자 */
    record Counts(int works, int worksBlocked, int permits, int permitsApproved, int permitsPending, int permitsBlocked,
                  int tbm, int tbmDone, int tbmMissing, int inspections, int correctiveOpen, int correctiveOverdue,
                  int nearMisses) {
    }

    /** 정해진 형식의 요약 문장. 숫자는 모두 stats 와 같다 */
    static String summarize(LocalDate date, Counts c, List<DashboardResponse.Item> checks) {
        StringBuilder sb = new StringBuilder();
        sb.append(date.getMonthValue()).append("월 ").append(date.getDayOfMonth()).append("일 ");
        if (c.works() == 0) {
            sb.append("등록된 작업이 없습니다.");
        } else {
            sb.append("작업 ").append(c.works()).append("건");
            if (c.worksBlocked() > 0) {
                sb.append("(차단 ").append(c.worksBlocked()).append("건)");
            }
            sb.append(". 작업허가 ").append(c.permits()).append("건 중 승인 ").append(c.permitsApproved())
                    .append("건, 승인 대기 ").append(c.permitsPending()).append("건, 차단 ").append(c.permitsBlocked()).append("건.");
            sb.append(" TBM ").append(c.tbmDone()).append("건 실시");
            if (c.tbmMissing() > 0) {
                sb.append(", 미실시 작업 ").append(c.tbmMissing()).append("건");
            }
            sb.append(".");
        }
        if (c.inspections() > 0) {
            sb.append(" 점검표 ").append(c.inspections()).append("건.");
        }
        if (c.nearMisses() > 0) {
            sb.append(" 아차사고 ").append(c.nearMisses()).append("건 보고.");
        }
        List<String> missing = checks.stream()
                .filter(i -> i.type() != IncompleteType.CORRECTIVE_OPEN && i.type() != IncompleteType.APPROVAL_OVERDUE)
                .map(i -> i.label() + " " + i.count() + "건").toList();
        if (!missing.isEmpty()) {
            sb.append(" 미완료: ").append(String.join(", ", missing)).append(".");
        }
        if (c.correctiveOpen() > 0) {
            sb.append(" 미조치 개선조치 ").append(c.correctiveOpen()).append("건");
            if (c.correctiveOverdue() > 0) {
                sb.append("(기한 지남 ").append(c.correctiveOverdue()).append("건)");
            }
            sb.append(".");
        }
        return sb.toString();
    }

    private static int count(List<Permit> permits, PermitStatus status) {
        return (int) permits.stream().filter(p -> p.getStatus() == status).count();
    }

    private static int countOf(Map<IncompleteType, DashboardResponse.Item> items, IncompleteType type) {
        DashboardResponse.Item i = items.get(type);
        return i == null ? 0 : i.count();
    }

    private static int overdueOf(Map<IncompleteType, DashboardResponse.Item> items, IncompleteType type) {
        DashboardResponse.Item i = items.get(type);
        return i == null ? 0 : (int) i.entries().stream().filter(DashboardResponse.Entry::overdue).count();
    }

    private static Map<String, Integer> byStatus(List<String> statuses) {
        Map<String, Integer> m = new TreeMap<>();
        statuses.forEach(s -> m.merge(s, 1, Integer::sum));
        return m;
    }
}
