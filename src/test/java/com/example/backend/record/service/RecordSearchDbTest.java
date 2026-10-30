package com.example.backend.record.service;

import com.example.backend.agent.dto.StartWorkRegisterRequest;
import com.example.backend.agent.service.AgentRunService;
import com.example.backend.approval.entity.Approval;
import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.service.ApprovalService;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.corrective.entity.ActionSource;
import com.example.backend.corrective.entity.CorrectiveAction;
import com.example.backend.corrective.repository.CorrectiveActionRepository;
import com.example.backend.history.repository.RecordAccessLogRepository;
import com.example.backend.permit.dto.AddMeasurementsRequest;
import com.example.backend.permit.dto.CreatePermitRequest;
import com.example.backend.permit.entity.MeasurementItem;
import com.example.backend.permit.entity.PermitType;
import com.example.backend.permit.service.PermitService;
import com.example.backend.record.dto.SearchRecordsRequest;
import com.example.backend.record.dto.SearchRecordsResult;
import com.example.backend.risk.entity.RiskAssessment;
import com.example.backend.risk.repository.RiskAssessmentRepository;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.tbm.entity.TbmRecord;
import com.example.backend.tbm.entity.TbmWork;
import com.example.backend.tbm.repository.TbmRecordRepository;
import com.example.backend.tbm.repository.TbmWorkRepository;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.work.dto.CreateWorkRequest;
import com.example.backend.work.repository.WorkRepository;
import com.example.backend.work.service.WorkService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기록 조회 도구 search_records: 조건 검사, 유형별 결과, 공식 기록만, 열람 기록 (실제 DB, 끝나면 롤백).
 * 날짜는 오늘 기준 (결재·이력 시각이 실제 시각이라).
 */
@SpringBootTest
@Transactional
@DisplayName("기록 조회 도구 search_records (실제 DB)")
class RecordSearchDbTest {

    private static final LocalDate DAY = LocalDate.now().minusDays(3);

    @Autowired RecordSearchService searchService;
    @Autowired AgentRunService runService;
    @Autowired WorkService workService;
    @Autowired PermitService permitService;
    @Autowired ApprovalService approvalService;
    @Autowired WorkRepository workRepository;
    @Autowired RiskAssessmentRepository riskRepository;
    @Autowired TbmRecordRepository tbmRepository;
    @Autowired TbmWorkRepository tbmWorkRepository;
    @Autowired CorrectiveActionRepository actionRepository;
    @Autowired RecordAccessLogRepository accessLogRepository;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired EntityManager em;

    private Long userId;
    private Long siteId;
    private Long runId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        userId = userRepository.save(User.bootstrapAdmin("record-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("기록현장-" + tag, SiteMode.GUIDE)).getId();
        runId = runService.startWorkRegister(userId, new StartWorkRegisterRequest(siteId, null, null, "지난주 기록 알려줘")).runId();
    }

    private SearchRecordsResult search(String type, Map<String, Object> extra) {
        Map<String, Object> params = new HashMap<>(Map.of("from", DAY.minusDays(30).toString(), "to", LocalDate.now().toString()));
        params.putAll(extra);
        return searchService.search(new SearchRecordsRequest(siteId, runId, type, params));
    }

    private Long work(WorkType type) {
        return workService.create(userId, new CreateWorkRequest(siteId, DAY, "작업 " + UUID.randomUUID().toString().substring(0, 4),
                null, type, DAY.atTime(13, 0), DAY.atTime(15, 0), 2)).workId();
    }

    private Long hotPermit(Long workId) {
        return permitService.create(userId, new CreatePermitRequest(workId, PermitType.HOT, DAY.atTime(13, 0), DAY.atTime(15, 0),
                "김감시", "이반장", Map.of("extinguisher_ready", true, "spark_prevention", true, "flammable_vapor_removed", true)))
                .permitId();
    }

    private void measureLel(Long permitId, String value) {
        permitService.addMeasurements(userId, permitId, new AddMeasurementsRequest(List.of(new AddMeasurementsRequest.Item(
                MeasurementItem.LEL, new BigDecimal(value), DAY.atTime(12, 30), "플랜지 주변", "박측정"))));
    }

    /** 측정·판정 흐름을 건너뛰고 승인까지 (예전 기록·시연용 가상 기록처럼 측정값 없이 승인된 허가 만들기) */
    private Approval approveDirectly(Long permitId) {
        em.flush();
        em.createNativeQuery("UPDATE permits SET status = 'PENDING' WHERE id = ?1").setParameter(1, permitId).executeUpdate();
        em.clear();
        Approval a = approvalService.request(ApprovalTarget.PERMIT, permitId, siteId, userId);
        approvalService.approve(userId, a.getId(), null);   // 안내 모드: 자기 승인 가능
        return a;
    }

    // ------------------------------------------------------------------ 조건 검사

    @Test
    @DisplayName("모르는 유형·없는 조건·기간 없음·기간 초과·다른 현장 실행은 400")
    void invalidRequests() {
        expectCode(() -> search("no_such_type", Map.of()), ErrorCode.INVALID_REQUEST);
        expectCode(() -> search("missing_tbm", Map.of("permitType", "HOT")), ErrorCode.INVALID_REQUEST);
        expectCode(() -> search("missing_tbm", Map.of("workType", "CRANE")), ErrorCode.INVALID_REQUEST);
        expectCode(() -> searchService.search(new SearchRecordsRequest(siteId, runId, "missing_tbm",
                Map.of("to", "2026-10-01"))), ErrorCode.INVALID_REQUEST);
        expectCode(() -> searchService.search(new SearchRecordsRequest(siteId, runId, "missing_tbm",
                Map.of("from", "2025-01-01", "to", "2026-10-01"))), ErrorCode.INVALID_REQUEST);
        Long otherSite = siteRepository.save(new Site("다른현장-" + UUID.randomUUID(), SiteMode.GUIDE)).getId();
        expectCode(() -> searchService.search(new SearchRecordsRequest(otherSite, runId, "missing_tbm",
                Map.of("from", "2026-10-01", "to", "2026-10-02"))), ErrorCode.INVALID_REQUEST);
    }

    // ------------------------------------------------------------------ 유형별

    @Test
    @DisplayName("측정값 없이 승인된 허가: 측정 규칙 항목이 빠진 승인 허가만, 이름·근거·조회 조건, 열람 기록")
    void missingMeasurements() {
        Long noMeasure = hotPermit(work(WorkType.HOT));
        approveDirectly(noMeasure);
        Long measured = hotPermit(work(WorkType.HOT));
        measureLel(measured, "3");
        approveDirectly(measured);
        hotPermit(work(WorkType.HOT));   // 승인 안 된 허가는 대상 아님

        SearchRecordsResult r = search("missing_measurements", Map.of("permitType", "HOT"));

        assertThat(r.records()).singleElement().satisfies(row -> {
            assertThat(row.get("permitId")).isEqualTo(noMeasure);
            assertThat(row.get("missingItems")).isEqualTo(List.of("LEL"));
            assertThat(row.get("approver")).isNotNull();
        });
        assertThat(r.evidence()).containsExactly(new SearchRecordsResult.Evidence("PERMIT", noMeasure));
        assertThat(r.conditions()).containsEntry("permitType", "HOT").containsEntry("siteId", siteId);
        assertThat(r.truncated()).isFalse();
        assertThat(accessLogRepository.findBySiteIdOrderByCreatedAtDescIdDesc(siteId, PageRequest.of(0, 5)).getContent())
                .singleElement().satisfies(log -> assertThat(log.getResourceId()).isEqualTo(runId));
    }

    @Test
    @DisplayName("차단 기록: 공식 판정 BLOCK 만, 위반 규칙 코드·측정값. 허가 통계에도 차단으로 잡힌다")
    void blockedAndStatistics() {
        Long blocked = hotPermit(work(WorkType.HOT));
        measureLel(blocked, "12");
        permitService.check(userId, blocked);
        Long approved = hotPermit(work(WorkType.HOT));
        approveDirectly(approved);

        SearchRecordsResult b = search("blocked_records", Map.of());
        assertThat(b.records()).singleElement().satisfies(row -> {
            assertThat(row.get("permitId")).isEqualTo(blocked);
            assertThat((List<?>) row.get("violations")).singleElement()
                    .satisfies(v -> assertThat(((Map<?, ?>) v).get("ruleCode")).isEqualTo("HOT_LEL_MAX"));
        });

        SearchRecordsResult s = search("permit_approval_statistics", Map.of());
        assertThat(s.summary()).containsEntry("permits", 2).containsEntry("approved", 1).containsEntry("blocked", 1);
        assertThat(s.summary().get("approvalRate")).isEqualTo(new BigDecimal("100.0"));
    }

    @Test
    @DisplayName("TBM 없는 작업과 위험성평가 실시율 (취소 작업 제외)")
    void tbmAndRiskRate() {
        Long withTbm = work(WorkType.GENERAL);
        Long withoutTbm = work(WorkType.GENERAL);
        Long canceled = work(WorkType.GENERAL);
        em.flush();
        em.createNativeQuery("UPDATE works SET status = 'CANCELED' WHERE id = ?1").setParameter(1, canceled).executeUpdate();
        em.clear();
        TbmRecord t = tbmRepository.saveAndFlush(new TbmRecord(siteId, DAY, null, null, userId));
        tbmWorkRepository.saveAndFlush(TbmWork.of(t, workRepository.findById(withTbm).orElseThrow()));
        riskRepository.saveAndFlush(RiskAssessment.daily(siteId, withTbm, DAY, userId));

        assertThat(search("missing_tbm", Map.of()).records()).extracting(row -> row.get("workId")).containsExactly(withoutTbm);
        SearchRecordsResult rate = search("risk_assessment_rate", Map.of("workType", "GENERAL"));
        assertThat(rate.summary()).containsEntry("works", 2).containsEntry("written", 1).containsEntry("missing", 1);
        assertThat(rate.summary().get("rate")).isEqualTo(new BigDecimal("50.0"));
    }

    @Test
    @DisplayName("대기 결재·결재자 통계(자기 승인)·변경 이력")
    void approvalsAndHistory() {
        Long pendingPermit = hotPermit(work(WorkType.HOT));
        em.flush();
        em.createNativeQuery("UPDATE permits SET status = 'PENDING' WHERE id = ?1").setParameter(1, pendingPermit).executeUpdate();
        em.clear();
        Approval pending = approvalService.request(ApprovalTarget.PERMIT, pendingPermit, siteId, userId);
        Long approvedPermit = hotPermit(work(WorkType.HOT));
        approveDirectly(approvedPermit);

        assertThat(search("pending_approvals", Map.of()).records()).extracting(row -> row.get("approvalId"))
                .containsExactly(pending.getId());
        assertThat(search("pending_approvals", Map.of("stepNo", 2)).records()).isEmpty();

        SearchRecordsResult stats = search("approver_statistics", Map.of("approverId", userId));
        assertThat(stats.summary()).containsEntry("decided", 1L).containsEntry("approved", 1L).containsEntry("selfApproved", 1L);

        SearchRecordsResult history = search("record_change_history", Map.of("recordType", "PERMIT", "recordId", approvedPermit));
        assertThat(history.records()).extracting(row -> row.get("action")).contains("SUBMIT", "APPROVE");
        assertThat(history.total()).isEqualTo(history.records().size());
        expectCode(() -> search("record_change_history", Map.of("recordId", approvedPermit)), ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("개선조치: 기본은 미완료만 목록, 조치율 = 완료 ÷ 전체(취소 제외)")
    void correctiveActions() {
        actionRepository.save(new CorrectiveAction(siteId, ActionSource.OTHER, null, "난간 보수", null, userId,
                LocalDate.now().minusDays(1), userId));
        CorrectiveAction done = new CorrectiveAction(siteId, ActionSource.OTHER, null, "표지 교체", null, userId,
                LocalDate.now().plusDays(3), userId);
        done.start();
        done.complete(LocalDateTime.now(), "교체 완료");
        actionRepository.save(done);

        SearchRecordsResult r = search("open_corrective_actions", Map.of());
        assertThat(r.records()).extracting(row -> row.get("title")).containsExactly("난간 보수");
        assertThat(r.records().get(0).get("overdue")).isEqualTo(true);
        assertThat(r.summary()).containsEntry("total", 2).containsEntry("completed", 1);
        assertThat(r.summary().get("completionRate")).isEqualTo(new BigDecimal("50.0"));
        assertThat(search("open_corrective_actions", Map.of("status", "DONE")).records()).hasSize(1);
    }
}
