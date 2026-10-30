package com.example.backend.dashboard.service;

import com.example.backend.approval.entity.Approval;
import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.service.ApprovalService;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.corrective.entity.ActionSource;
import com.example.backend.corrective.entity.CorrectiveAction;
import com.example.backend.corrective.repository.CorrectiveActionRepository;
import com.example.backend.dashboard.dto.DashboardResponse;
import com.example.backend.dashboard.dto.DashboardSummary;
import com.example.backend.dashboard.dto.IncompleteType;
import com.example.backend.notification.repository.NotificationRepository;
import com.example.backend.permit.dto.AddMeasurementsRequest;
import com.example.backend.permit.dto.CreatePermitRequest;
import com.example.backend.permit.entity.MeasurementItem;
import com.example.backend.permit.entity.PermitType;
import com.example.backend.permit.service.PermitService;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMember;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.entity.SiteRole;
import com.example.backend.site.repository.SiteMemberRepository;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.tbm.entity.TbmRecord;
import com.example.backend.tbm.entity.TbmWork;
import com.example.backend.tbm.repository.TbmRecordRepository;
import com.example.backend.tbm.repository.TbmWorkRepository;
import com.example.backend.user.entity.EmailVerifiedVia;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserRole;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.work.dto.CreateWorkRequest;
import com.example.backend.work.entity.Work;
import com.example.backend.work.repository.WorkRepository;
import com.example.backend.work.service.WorkService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대시보드 미완료 항목과 마감 알림 (실제 DB, 끝나면 롤백).
 * 지난 날짜(2026-10-01)는 마감이 모두 지났고, 먼 날짜(2027-01-05)는 아직 안 지났다 (현장 기본 마감 TBM 09:00, 안전일지 18:00).
 */
@SpringBootTest
@Transactional
@DisplayName("대시보드·마감 알림 (실제 DB)")
class DashboardDbTest {

    private static final LocalDate PAST = LocalDate.of(2026, 10, 1);
    private static final LocalDate FUTURE = LocalDate.of(2027, 1, 5);

    @Autowired DashboardService dashboardService;
    @Autowired DeadlineService deadlineService;
    @Autowired ApprovalService approvalService;
    @Autowired WorkService workService;
    @Autowired PermitService permitService;
    @Autowired WorkRepository workRepository;
    @Autowired TbmRecordRepository tbmRepository;
    @Autowired TbmWorkRepository tbmWorkRepository;
    @Autowired CorrectiveActionRepository actionRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired SiteMemberRepository siteMemberRepository;
    @Autowired EntityManager em;

    private Long adminId;
    private Long approverId;
    private Long siteId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        adminId = userRepository.save(User.bootstrapAdmin("dash-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("대시보드현장-" + tag, SiteMode.GUIDE)).getId();
        User approver = new User("dash-appr-" + tag + "@example.com", "HASH", "승인자");
        approver.verifyEmail(EmailVerifiedVia.ADMIN, LocalDateTime.now());
        approver.approve(UserRole.MEMBER);
        approverId = userRepository.save(approver).getId();
        siteMemberRepository.save(new SiteMember(approverId, siteId, SiteRole.APPROVER, adminId));
    }

    private Long work(LocalDate date, WorkType type) {
        return workService.create(adminId, new CreateWorkRequest(siteId, date, "작업 " + type, null, type,
                date.atTime(13, 0), date.atTime(15, 0), 2)).workId();
    }

    private Long hotPermit(Long workId, LocalDate date) {
        return permitService.create(adminId, new CreatePermitRequest(workId, PermitType.HOT, date.atTime(13, 0),
                date.atTime(15, 0), "김감시", "이반장", Map.of())).permitId();
    }

    private DashboardResponse.Item item(DashboardResponse d, IncompleteType type) {
        return d.items().stream().filter(i -> i.type() == type).findFirst().orElseThrow();
    }

    /** 작업과 연결된 TBM. held 면 실시 완료(참석 2명) */
    private Long tbm(Long workId, LocalDate date, boolean held) {
        TbmRecord t = new TbmRecord(siteId, date, null, null, adminId);
        if (held) {
            t.complete(date.atTime(8, 30), 2);
        }
        t = tbmRepository.saveAndFlush(t);
        Work w = workRepository.findById(workId).orElseThrow();
        tbmWorkRepository.saveAndFlush(TbmWork.of(t, w));
        return t.getId();
    }

    // ------------------------------------------------------------------ 그날 현황

    @Test
    @DisplayName("지난 날: 측정값 없는 화기 허가·TBM 없는 작업·안전일지 없음이 지연으로 잡히고, 항목은 항상 9개")
    void pastDay_overdue() {
        Long hotWork = work(PAST, WorkType.HOT);
        Long permitId = hotPermit(hotWork, PAST);

        DashboardResponse d = dashboardService.day(adminId, siteId, PAST);

        assertThat(d.items()).extracting(DashboardResponse.Item::type).containsExactly(IncompleteType.values());
        assertThat(d.works().total()).isEqualTo(1);
        assertThat(d.deadlines().tbmPassed()).isTrue();
        assertThat(item(d, IncompleteType.MEASUREMENT_MISSING).entries()).singleElement().satisfies(e -> {
            assertThat(e.targetId()).isEqualTo(permitId);
            assertThat(e.detail()).isEqualTo("LEL");
            assertThat(e.overdue()).isTrue();   // 작업 시작(13:00)이 지남
        });
        assertThat(item(d, IncompleteType.TBM_MISSING).entries()).extracting(DashboardResponse.Entry::workId)
                .containsExactly(hotWork);
        assertThat(item(d, IncompleteType.TBM_MISSING).overdue()).isTrue();
        assertThat(item(d, IncompleteType.DAILY_LOG_MISSING).count()).isEqualTo(1);
        assertThat(d.teams()).singleElement().satisfies(t -> {
            assertThat(t.teamName()).isEqualTo("팀 미지정");
            assertThat(t.incomplete()).isEqualTo(2);   // 측정값 + TBM
        });
    }

    @Test
    @DisplayName("작업 전 측정값을 넣으면 측정값 미입력에서 빠진다")
    void measured_notMissing() {
        Long hotWork = work(PAST, WorkType.HOT);
        Long permitId = hotPermit(hotWork, PAST);
        permitService.addMeasurements(adminId, permitId, new AddMeasurementsRequest(List.of(new AddMeasurementsRequest.Item(
                MeasurementItem.LEL, new BigDecimal("3"), PAST.atTime(12, 30), "플랜지 주변", "박측정"))));

        assertThat(item(dashboardService.day(adminId, siteId, PAST), IncompleteType.MEASUREMENT_MISSING).count()).isZero();
    }

    @Test
    @DisplayName("앞날: TBM·안전일지 미완료는 잡히지만 마감 전이라 지연 아님")
    void futureDay_notOverdue() {
        work(FUTURE, WorkType.GENERAL);

        DashboardResponse d = dashboardService.day(adminId, siteId, FUTURE);

        assertThat(d.deadlines().tbmPassed()).isFalse();
        assertThat(item(d, IncompleteType.TBM_MISSING).count()).isEqualTo(1);
        assertThat(item(d, IncompleteType.TBM_MISSING).overdue()).isFalse();
        assertThat(item(d, IncompleteType.DAILY_LOG_MISSING).overdue()).isFalse();
        assertThat(item(d, IncompleteType.MEASUREMENT_MISSING).count()).isZero();   // 일반 작업은 허가 없음
    }

    @Test
    @DisplayName("취소된 작업은 빼고, 차단된 작업은 전체에 넣고 따로 센다. 작업이 없는 날은 안전일지도 미완료 아님")
    void canceledAndBlocked() {
        Long blocked = work(PAST, WorkType.GENERAL);
        Long canceled = work(PAST, WorkType.GENERAL);
        em.createNativeQuery("UPDATE works SET status = 'BLOCKED' WHERE id = ?1").setParameter(1, blocked).executeUpdate();
        em.createNativeQuery("UPDATE works SET status = 'CANCELED' WHERE id = ?1").setParameter(1, canceled).executeUpdate();
        em.clear();

        DashboardResponse d = dashboardService.day(adminId, siteId, PAST);
        assertThat(d.works().total()).isEqualTo(1);
        assertThat(d.works().blocked()).isEqualTo(1);
        assertThat(item(d, IncompleteType.TBM_MISSING).entries()).extracting(DashboardResponse.Entry::workId)
                .containsExactly(blocked);

        assertThat(item(dashboardService.day(adminId, siteId, PAST.plusDays(1)), IncompleteType.DAILY_LOG_MISSING).count())
                .isZero();
    }

    @Test
    @DisplayName("TBM 이 연결되면 TBM 미실시에서 빠지고, 실시 완료된 TBM 은 서명·사진 미완료로 잡힌다")
    void tbmHeld_signatureAndPhoto() {
        Long w = work(PAST, WorkType.GENERAL);
        Long tbmId = tbm(w, PAST, true);

        DashboardResponse d = dashboardService.day(adminId, siteId, PAST);

        assertThat(item(d, IncompleteType.TBM_MISSING).count()).isZero();
        assertThat(item(d, IncompleteType.SIGNATURE_MISSING).entries()).singleElement().satisfies(e -> {
            assertThat(e.targetId()).isEqualTo(tbmId);
            assertThat(e.detail()).isEqualTo("서명 0/2");
        });
        assertThat(item(d, IncompleteType.PHOTO_MISSING).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("작성 중 TBM 은 서명·사진을 아직 따지지 않는다")
    void tbmDraft_noSignatureCheck() {
        tbm(work(PAST, WorkType.GENERAL), PAST, false);
        DashboardResponse d = dashboardService.day(adminId, siteId, PAST);
        assertThat(item(d, IncompleteType.SIGNATURE_MISSING).count()).isZero();
        assertThat(item(d, IncompleteType.PHOTO_MISSING).count()).isZero();
    }

    @Test
    @DisplayName("개선조치는 기한이 지나면 지연, 승인 지연은 결재 마감이 지난 대기 결재")
    void currentItems() {
        actionRepository.save(new CorrectiveAction(siteId, ActionSource.OTHER, null, "난간 보수", null, approverId,
                LocalDate.now().minusDays(1), adminId));
        actionRepository.save(new CorrectiveAction(siteId, ActionSource.OTHER, null, "표지 교체", null, adminId,
                LocalDate.now().plusDays(3), adminId));
        Long permitId = hotPermit(work(PAST, WorkType.HOT), PAST);
        Approval a = approvalService.request(ApprovalTarget.PERMIT, permitId, siteId, adminId);
        em.flush();
        em.createNativeQuery("UPDATE approvals SET requested_at = ?1, due_at = ?2 WHERE id = ?3")
                .setParameter(1, PAST.atTime(8, 0)).setParameter(2, PAST.atTime(9, 0)).setParameter(3, a.getId())
                .executeUpdate();
        em.clear();

        DashboardResponse d = dashboardService.day(adminId, siteId, PAST);

        assertThat(item(d, IncompleteType.CORRECTIVE_OPEN).entries()).extracting(DashboardResponse.Entry::title,
                DashboardResponse.Entry::overdue).containsExactly(
                org.assertj.core.groups.Tuple.tuple("난간 보수", true), org.assertj.core.groups.Tuple.tuple("표지 교체", false));
        assertThat(item(d, IncompleteType.APPROVAL_OVERDUE).entries()).extracting(DashboardResponse.Entry::targetId)
                .containsExactly(a.getId());
    }

    // ------------------------------------------------------------------ 기간 집계

    @Test
    @DisplayName("기간 집계: 날짜마다 한 줄(작업 없는 날 포함), 합계, 최대 31일")
    void summary() {
        work(PAST, WorkType.GENERAL);
        work(PAST.plusDays(2), WorkType.GENERAL);
        work(PAST.plusDays(2), WorkType.GENERAL);

        DashboardSummary s = dashboardService.summary(adminId, siteId, PAST, PAST.plusDays(2));

        assertThat(s.days()).extracting(DashboardSummary.Day::works).containsExactly(1, 0, 2);
        assertThat(s.totals().get(IncompleteType.TBM_MISSING)).isEqualTo(3);
        assertThat(s.totals().get(IncompleteType.DAILY_LOG_MISSING)).isEqualTo(2);
        assertThat(s.totals()).doesNotContainKey(IncompleteType.APPROVAL_OVERDUE);
        expectCode(() -> dashboardService.summary(adminId, siteId, PAST, PAST.plusDays(31)), ErrorCode.INVALID_REQUEST);
        expectCode(() -> dashboardService.summary(adminId, siteId, PAST, PAST.minusDays(1)), ErrorCode.INVALID_REQUEST);
    }

    // ------------------------------------------------------------------ 마감 알림

    @Test
    @DisplayName("마감 알림: 마감 전에는 안 보내고, 지나면 TBM·안전일지 한 번씩 (다시 돌려도 중복 없음)")
    void deadlineNotifications() {
        work(PAST, WorkType.GENERAL);

        assertThat(deadlineService.checkSite(siteId, PAST.atTime(8, 0))).isZero();
        assertThat(deadlineService.checkSite(siteId, PAST.atTime(10, 0))).isEqualTo(1);   // TBM 만
        assertThat(deadlineService.checkSite(siteId, PAST.atTime(19, 0))).isEqualTo(1);   // 안전일지
        assertThat(deadlineService.checkSite(siteId, PAST.atTime(19, 5))).isZero();
        assertThat(notificationRepository.findByDedupeKey("DEADLINE:TBM:" + siteId + ":" + PAST)).isPresent();
        assertThat(notificationRepository.findByDedupeKey("DEADLINE:DAILY_LOG:" + siteId + ":" + PAST)).isPresent();
    }

    @Test
    @DisplayName("기한 지난 개선조치는 담당자에게 하루 한 번, 승인 지연은 결재 가능자에게 단계마다 한 번")
    void actionAndApprovalReminders() {
        CorrectiveAction action = actionRepository.save(new CorrectiveAction(siteId, ActionSource.OTHER, null, "난간 보수",
                null, approverId, PAST.minusDays(1), adminId));
        assertThat(deadlineService.checkSite(siteId, PAST.atTime(8, 0))).isEqualTo(1);
        assertThat(notificationRepository.findByDedupeKey("ACTION_DUE:" + action.getId() + ":" + PAST)).isPresent();

        Long permitId = hotPermit(work(FUTURE, WorkType.HOT), FUTURE);
        Approval a = approvalService.request(ApprovalTarget.PERMIT, permitId, siteId, adminId);
        em.flush();
        LocalDateTime later = a.getDueAt().plusMinutes(1);
        deadlineService.remindApprovals(later);
        deadlineService.remindApprovals(later.plusMinutes(5));
        em.flush();
        assertThat(notificationRepository.findByDedupeKey("DEADLINE:APPROVAL:" + a.getId() + ":1")).isPresent();
    }
}
