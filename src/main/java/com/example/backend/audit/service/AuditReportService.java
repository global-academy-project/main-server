package com.example.backend.audit.service;

import com.example.backend.agent.client.AgentCallException;
import com.example.backend.agent.client.AgentClient;
import com.example.backend.agent.client.AgentReply;
import com.example.backend.audit.dto.AuditReportResponse;
import com.example.backend.audit.dto.CreateAuditReportRequest;
import com.example.backend.audit.dto.UpdateAuditReportRequest;
import com.example.backend.audit.entity.AuditReport;
import com.example.backend.audit.entity.AuditReportStatus;
import com.example.backend.audit.repository.AuditReportRepository;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.corrective.entity.ActionStatus;
import com.example.backend.corrective.entity.CorrectiveAction;
import com.example.backend.corrective.repository.CorrectiveActionRepository;
import com.example.backend.history.entity.AccessAction;
import com.example.backend.history.entity.AccessResource;
import com.example.backend.history.entity.RecordAccessLog;
import com.example.backend.history.repository.RecordAccessLogRepository;
import com.example.backend.record.dto.SearchRecordsResult;
import com.example.backend.record.service.RecordSearchService;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 감사보고서 (웹 /audit-reports). 현장 관리자 이상만.
 * <ol>
 *   <li>만들기: 기간·조회 항목 → 서버가 기록 조회(search_records 와 같은 공식 기록 집계)로 항목별 집계를 만들어 저장 (GENERATING)</li>
 *   <li>커밋 뒤 AI 서버 /api/v1/agent/audit-report 에 집계를 보내 항목별 요약과 수치 검증을 받는다 (에이전트 실행기)</li>
 *   <li>AI 가 실패해도 보고서와 집계는 남고 상태만 FAILED (다시 요약하거나 사람이 쓴다)</li>
 * </ol>
 * 집계 원본은 data_snapshot 으로 고정. 사람은 제목·종합 의견·항목별 요약만 고친다. 열람·생성은 record_access_logs 에 남긴다.
 */
@Service
public class AuditReportService {

    private static final Logger log = LoggerFactory.getLogger(AuditReportService.class);

    /** 웹 화면의 조회 항목 순서와 제목 */
    static final Map<String, String> QUERY_TITLES;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("risk_assessment_rate", "위험성평가 실시율");
        m.put("permit_approval_statistics", "작업허가 승인 현황");
        m.put("corrective_action_rate", "지적사항 조치율");
        m.put("blocked_records", "차단 기록");
        m.put("missing_measurements", "측정값 없이 승인된 허가");
        QUERY_TITLES = Collections.unmodifiableMap(m);
    }

    private final AuditReportRepository reportRepository;
    private final RecordSearchService recordSearchService;
    private final CorrectiveActionRepository actionRepository;
    private final RecordAccessLogRepository accessLogRepository;
    private final UserRepository userRepository;
    private final AgentClient agentClient;
    private final SiteGuard siteGuard;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final int maxDays;
    private final EntityManager em;
    private final ApplicationEventPublisher events;
    private final long staleAfterSeconds;

    public AuditReportService(AuditReportRepository reportRepository, RecordSearchService recordSearchService,
                              CorrectiveActionRepository actionRepository, RecordAccessLogRepository accessLogRepository,
                              UserRepository userRepository, AgentClient agentClient, SiteGuard siteGuard,
                              JsonMapper jsonMapper, Clock clock, PlatformTransactionManager transactionManager,
                              @Value("${app.records.max-days}") int maxDays, EntityManager em,
                              ApplicationEventPublisher events,
                              @Value("${app.agent.stale-after-seconds}") long staleAfterSeconds) {
        this.reportRepository = reportRepository;
        this.recordSearchService = recordSearchService;
        this.actionRepository = actionRepository;
        this.accessLogRepository = accessLogRepository;
        this.userRepository = userRepository;
        this.agentClient = agentClient;
        this.siteGuard = siteGuard;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.maxDays = maxDays;
        this.em = em;
        this.events = events;
        this.staleAfterSeconds = staleAfterSeconds;
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public List<AuditReportResponse> list(Long userId, Long siteId) {
        siteGuard.requireSiteAdmin(userId, siteId);
        List<AuditReport> reports = reportRepository.findBySiteIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(siteId);
        Map<Long, String> names = names(reports);
        return reports.stream().map(r -> response(r, names, false)).toList();
    }

    @Transactional
    public AuditReportResponse get(Long userId, Long siteId, Long reportId) {
        AuditReport r = load(userId, siteId, reportId);
        accessLog(userId, r, AccessAction.VIEW, null);
        return response(r, names(List.of(r)), true);
    }

    // ------------------------------------------------------------------ 만들기

    /**
     * 집계 → 저장(GENERATING) → 커밋 뒤 AI 요약(AuditReportSummaryListener → runSummary).
     * 웹은 바로 상세로 가서 요약이 끝날 때까지 상태를 다시 조회한다.
     */
    @Transactional
    public AuditReportResponse create(Long userId, Long siteId, CreateAuditReportRequest r) {
        siteGuard.requireSiteAdmin(userId, siteId);
        List<String> types = checkRequest(r);
        ArrayNode items = jsonMapper.createArrayNode();
        ObjectNode snapshot = jsonMapper.createObjectNode();
        for (String type : types) {
            ObjectNode item = aggregate(siteId, type, r.dateFrom(), r.dateTo());
            snapshot.set(type, item.deepCopy());
            items.add(item);
        }
        String title = "감사보고서 " + r.dateFrom() + " ~ " + r.dateTo();
        AuditReport report = reportRepository.saveAndFlush(new AuditReport(siteId, title, r.dateFrom(), r.dateTo(),
                String.join(",", types), AuditReportStatus.GENERATING, null, null, jsonMapper.writeValueAsString(items),
                jsonMapper.writeValueAsString(snapshot), userId));
        em.refresh(report);   // 만든 시각(created_at)은 DB 기본값
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("from", r.dateFrom().toString());
        detail.put("to", r.dateTo().toString());
        detail.put("queryTypes", types);
        accessLog(userId, report, AccessAction.QUERY, detail);
        events.publishEvent(new AuditReportRequestedEvent(report.getId()));
        return response(report, names(List.of(report)), true);
    }

    /** AI 요약 다시 하기: 요약 실패(FAILED), 또는 요약 중으로 app.agent.stale-after-seconds 넘게 멈춘 보고서만 */
    @Transactional
    public AuditReportResponse retrySummary(Long userId, Long siteId, Long reportId) {
        AuditReport report = load(userId, siteId, reportId);
        boolean stuck = report.getStatus() == AuditReportStatus.GENERATING && report.getUpdatedAt() != null
                && report.getUpdatedAt().isBefore(LocalDateTime.now(clock).minusSeconds(staleAfterSeconds));
        if (report.getStatus() != AuditReportStatus.FAILED && !stuck) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "요약에 실패한 감사보고서만 다시 요약할 수 있습니다.");
        }
        run(report::restartSummary);
        reportRepository.saveAndFlush(report);
        events.publishEvent(new AuditReportRequestedEvent(report.getId()));
        return response(report, names(List.of(report)), true);
    }

    /** 커밋 뒤 실행 (트랜잭션 밖): AI 요약 → 결과 반영. GENERATING 이 아니면 아무것도 안 한다 */
    public void runSummary(Long reportId) {
        AuditReport report = reportRepository.findById(reportId).orElse(null);
        if (report == null || report.getStatus() != AuditReportStatus.GENERATING || report.isDeleted()) {
            return;
        }
        Summary summary;
        try {
            summary = summarize(report, (ArrayNode) jsonMapper.readTree(report.getItemsJson()));
        } catch (RuntimeException e) {
            log.error("감사보고서 요약 처리 실패: reportId={}", reportId, e);
            summary = new Summary(AuditReportStatus.FAILED, null, "요약 결과를 처리하지 못했습니다.",
                    (ArrayNode) jsonMapper.readTree(report.getItemsJson()));
        }
        Summary result = summary;
        tx.executeWithoutResult(status -> reportRepository.findById(reportId)
                .filter(x -> x.getStatus() == AuditReportStatus.GENERATING && !x.isDeleted())
                .ifPresent(x -> {
                    x.applySummary(result.status(), result.overall(), result.failure(), jsonMapper.writeValueAsString(result.items()));
                    reportRepository.saveAndFlush(x);
                }));
    }

    // ------------------------------------------------------------------ 고치기·삭제

    @Transactional
    public AuditReportResponse update(Long userId, Long siteId, Long reportId, UpdateAuditReportRequest r) {
        AuditReport report = load(userId, siteId, reportId);
        if (r.lockVersion() == null || r.lockVersion() != report.getLockVersion()) {
            throw new BusinessException(ErrorCode.CONFLICT_MODIFIED);
        }
        ArrayNode items = (ArrayNode) jsonMapper.readTree(report.getItemsJson());
        Map<String, String> edited = new HashMap<>();
        for (UpdateAuditReportRequest.Item item : r.items() == null ? List.<UpdateAuditReportRequest.Item>of() : r.items()) {
            if (item.queryType() != null) {
                edited.put(item.queryType(), item.summary());
            }
        }
        for (int i = 0; i < items.size(); i++) {
            JsonNode node = items.get(i);
            String type = node.path("queryType").asString();
            if (edited.containsKey(type)) {
                ((ObjectNode) node).put("summary", edited.get(type) == null ? "" : edited.get(type));
            }
        }
        run(() -> report.edit(r.title(), blankToNull(r.summary()), jsonMapper.writeValueAsString(items)));
        reportRepository.saveAndFlush(report);
        return response(report, names(List.of(report)), true);
    }

    @Transactional
    public void delete(Long userId, Long siteId, Long reportId) {
        AuditReport report = load(userId, siteId, reportId);
        run(() -> report.delete(userId, LocalDateTime.now(clock)));
        reportRepository.saveAndFlush(report);
    }

    // ------------------------------------------------------------------ 집계

    private List<String> checkRequest(CreateAuditReportRequest r) {
        if (r.dateFrom().isAfter(r.dateTo())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "조회 시작일은 종료일보다 늦을 수 없습니다.");
        }
        if (ChronoUnit.DAYS.between(r.dateFrom(), r.dateTo()) + 1 > maxDays) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "조회 기간은 " + maxDays + "일까지입니다.");
        }
        Set<String> chosen = new LinkedHashSet<>(r.queryTypes());
        if (chosen.size() != r.queryTypes().size()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "조회 항목이 중복됐습니다.");
        }
        for (String type : chosen) {
            if (!QUERY_TITLES.containsKey(type)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "감사보고서에 없는 조회 항목입니다: " + type);
            }
        }
        // 화면 순서로 정렬
        return QUERY_TITLES.keySet().stream().filter(chosen::contains).toList();
    }

    /** 항목 하나: {queryType, title, summary:"", verification:null, stats, total, truncated, records, evidence} */
    ObjectNode aggregate(Long siteId, String type, LocalDate from, LocalDate to) {
        ObjectNode item = jsonMapper.createObjectNode();
        item.put("queryType", type);
        item.put("title", QUERY_TITLES.get(type));
        item.put("summary", "");
        item.putNull("verification");
        if ("corrective_action_rate".equals(type)) {
            correctiveRate(item, siteId, from, to);
            return item;
        }
        SearchRecordsResult result = recordSearchService.query(siteId, type, Map.of("from", from.toString(), "to", to.toString()));
        item.set("stats", jsonMapper.valueToTree(result.summary()));
        item.put("total", result.total());
        item.put("truncated", result.truncated());
        item.set("records", jsonMapper.valueToTree(result.records()));
        item.set("evidence", jsonMapper.valueToTree(result.evidence()));
        return item;
    }

    /** 지적사항 조치율: 기간 안에 등록된 개선조치(취소 제외) 중 완료·확인된 비율 */
    private void correctiveRate(ObjectNode item, Long siteId, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        List<CorrectiveAction> actions = actionRepository.findBySiteIdOrderByDueDateAscIdAsc(siteId).stream()
                .filter(a -> a.getCreatedAt() != null)
                .filter(a -> !a.getCreatedAt().toLocalDate().isBefore(from) && !a.getCreatedAt().toLocalDate().isAfter(to))
                .filter(a -> a.getStatus() != ActionStatus.CANCELED)
                .toList();
        long done = actions.stream().filter(a -> a.getStatus() == ActionStatus.DONE || a.getStatus() == ActionStatus.VERIFIED).count();
        long verified = actions.stream().filter(a -> a.getStatus() == ActionStatus.VERIFIED).count();
        long overdue = actions.stream().filter(a -> a.isOverdue(today)).count();
        ObjectNode stats = item.putObject("stats");
        stats.put("actions", actions.size());
        stats.put("completed", done);
        stats.put("verified", verified);
        stats.put("open", actions.size() - done);
        stats.put("overdue", overdue);
        if (actions.isEmpty()) {
            stats.putNull("completionRate");
        } else {
            stats.put("completionRate", BigDecimal.valueOf(done * 100).divide(BigDecimal.valueOf(actions.size()), 1, RoundingMode.HALF_UP));
        }
        item.put("total", actions.size());
        item.put("truncated", false);
        ArrayNode records = item.putArray("records");
        ArrayNode evidence = item.putArray("evidence");
        for (CorrectiveAction a : actions) {
            ObjectNode row = records.addObject();
            row.put("actionId", a.getId());
            row.put("title", a.getTitle());
            row.put("status", a.getStatus().name());
            row.put("dueDate", a.getDueDate().toString());
            row.put("sourceType", a.getSourceType().name());
            ObjectNode ev = evidence.addObject();
            ev.put("type", "CORRECTIVE_ACTION");
            ev.put("id", a.getId());
        }
    }

    // ------------------------------------------------------------------ AI 요약

    record Summary(AuditReportStatus status, String overall, String failure, ArrayNode items) {
    }

    /** AI 서버에 집계를 보내 항목별 요약·검증을 받는다. 실패하면 집계만 남기고 FAILED */
    Summary summarize(AuditReport report, ArrayNode items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reportId", report.getId());
        body.put("siteId", report.getSiteId());
        body.put("dateFrom", report.getDateFrom().toString());
        body.put("dateTo", report.getDateTo().toString());
        List<Map<String, Object>> sent = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("queryType", item.path("queryType").asString());
            m.put("title", item.path("title").asString());
            m.put("stats", jsonMapper.convertValue(item.path("stats"), Map.class));
            m.put("records", jsonMapper.convertValue(item.path("records"), List.class));
            m.put("evidence", jsonMapper.convertValue(item.path("evidence"), List.class));
            sent.add(m);
        }
        body.put("items", sent);
        try {
            AgentReply reply = agentClient.auditReport(body);
            JsonNode data = reply.data();
            Map<String, JsonNode> byType = new HashMap<>();
            JsonNode results = data.path("items");
            for (int i = 0; i < results.size(); i++) {
                JsonNode d = results.path(i);
                byType.put(d.path("queryType").asString(), d);
            }
            ArrayNode merged = items.deepCopy();
            for (int i = 0; i < merged.size(); i++) {
                JsonNode node = merged.get(i);
                JsonNode d = byType.get(node.path("queryType").asString());
                if (d != null) {
                    ((ObjectNode) node).put("summary", d.path("summary").asString());
                    if (d.path("verification").isObject()) {
                        ((ObjectNode) node).set("verification", d.path("verification"));
                    } else {
                        ((ObjectNode) node).putNull("verification");
                    }
                }
            }
            AuditReportStatus status = switch (data.path("status").asString()) {
                case "DRAFT" -> AuditReportStatus.DRAFT;
                case "REVIEW_REQUIRED" -> AuditReportStatus.REVIEW_REQUIRED;
                default -> AuditReportStatus.FAILED;
            };
            String overall = data.path("overallVerification").asString();
            return new Summary(status, "PASS".equals(overall) || "FAIL".equals(overall) ? overall : null,
                    status == AuditReportStatus.FAILED ? "AI 요약이 완료되지 않았습니다." : null, merged);
        } catch (AgentCallException e) {
            log.warn("감사보고서 AI 요약 실패: reportId={} code={} reason={}", report.getId(), e.getCode(), e.getMessage());
            return new Summary(AuditReportStatus.FAILED, null, e.getMessage(), items);
        }
    }

    // ------------------------------------------------------------------ 내부

    private AuditReport load(Long userId, Long siteId, Long reportId) {
        siteGuard.requireSiteAdmin(userId, siteId);
        AuditReport r = reportRepository.findById(reportId)
                .filter(x -> x.getSiteId().equals(siteId) && !x.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "감사보고서를 찾을 수 없습니다."));
        return r;
    }

    private void accessLog(Long userId, AuditReport r, AccessAction action, Map<String, Object> detail) {
        accessLogRepository.save(new RecordAccessLog(userId, r.getSiteId(), AccessResource.AUDIT_REPORT, r.getId(), action,
                detail == null ? null : jsonMapper.writeValueAsString(detail), null));
    }

    private Map<Long, String> names(List<AuditReport> reports) {
        Set<Long> ids = new HashSet<>();
        reports.forEach(r -> ids.add(r.getCreatedBy()));
        Map<Long, String> names = new HashMap<>();
        for (User u : userRepository.findAllById(ids)) {
            names.put(u.getId(), u.getName());
        }
        return names;
    }

    private AuditReportResponse response(AuditReport r, Map<Long, String> names, boolean withItems) {
        JsonNode items = withItems ? jsonMapper.readTree(r.getItemsJson()) : jsonMapper.createArrayNode();
        return new AuditReportResponse(r.getId(), r.getSiteId(), r.getTitle(), r.getDateFrom(), r.getDateTo(),
                List.of(r.getQueryTypes().split(",")), r.getStatus(), r.getStatus().label(), r.getOverallVerification(),
                r.getFailureReason(), r.getSummary(), items, r.getCreatedBy(), names.get(r.getCreatedBy()),
                r.getCreatedAt(), r.getUpdatedAt(), r.getLockVersion());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static void run(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_STATE, e.getMessage());
        }
    }
}
