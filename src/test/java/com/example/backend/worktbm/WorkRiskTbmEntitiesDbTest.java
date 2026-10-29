package com.example.backend.worktbm;

import com.example.backend.rule.entity.WorkType;
import com.example.backend.risk.entity.*;
import com.example.backend.risk.repository.RiskAssessmentItemRepository;
import com.example.backend.risk.repository.RiskAssessmentRepository;
import com.example.backend.site.entity.ChemicalUnit;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.entity.WorkZone;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.site.repository.WorkZoneRepository;
import com.example.backend.tbm.entity.TbmAttendee;
import com.example.backend.tbm.entity.TbmRecord;
import com.example.backend.tbm.entity.TbmStatus;
import com.example.backend.tbm.entity.TbmWork;
import com.example.backend.tbm.repository.TbmAttendeeRepository;
import com.example.backend.tbm.repository.TbmRecordRepository;
import com.example.backend.tbm.repository.TbmWorkRepository;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.work.entity.*;
import com.example.backend.work.repository.WorkMaterialRepository;
import com.example.backend.work.repository.WorkRepository;
import com.example.backend.work.repository.WorkRiskFactorRepository;
import com.example.backend.work.repository.WorkWorkerRepository;
import com.example.backend.worker.entity.Team;
import com.example.backend.worker.entity.Worker;
import com.example.backend.worker.entity.WorkerSite;
import com.example.backend.worker.repository.TeamRepository;
import com.example.backend.worker.repository.WorkerRepository;
import com.example.backend.worker.repository.WorkerSiteRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 작업·위험성평가·TBM 엔티티를 실제 로컬 DB로 확인 (끝나면 롤백).
 * V3·V4 의 CHECK·외래키·유니크, V9 의 잠금 트리거(제출·승인 후 수정 불가)가 그대로 동작하는지 본다.
 */
@SpringBootTest
@Transactional
@DisplayName("작업·위험성평가·TBM 엔티티 DB 통합")
class WorkRiskTbmEntitiesDbTest {

    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired WorkZoneRepository zoneRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired WorkerRepository workerRepository;
    @Autowired WorkerSiteRepository workerSiteRepository;
    @Autowired WorkRepository workRepository;
    @Autowired WorkRiskFactorRepository factorRepository;
    @Autowired WorkMaterialRepository materialRepository;
    @Autowired WorkWorkerRepository workWorkerRepository;
    @Autowired RiskAssessmentRepository assessmentRepository;
    @Autowired RiskAssessmentItemRepository itemRepository;
    @Autowired TbmRecordRepository tbmRepository;
    @Autowired TbmWorkRepository tbmWorkRepository;
    @Autowired TbmAttendeeRepository attendeeRepository;
    @Autowired EntityManager em;

    private final LocalDate today = LocalDate.now();
    private Long userId;
    private Long siteId;
    private WorkZone tank;
    private Team team;
    private Worker welder;
    private Worker watcher;
    private Work work;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        userId = userRepository.save(User.bootstrapAdmin("wrt-test-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("테스트현장-" + tag, SiteMode.VERIFY)).getId();
        tank = zoneRepository.save(new WorkZone(siteId, "T-101", "1번 저장탱크", "TANK", true, null));
        team = teamRepository.save(Team.internal("설비팀-" + tag));
        welder = workerRepository.save(new Worker(team.getId(), "김용접", null, "용접", null));
        watcher = workerRepository.save(new Worker(team.getId(), "최감시", null, "감시", null));
        workerSiteRepository.save(new WorkerSite(welder.getId(), siteId, today.minusDays(7)));
        workerSiteRepository.save(new WorkerSite(watcher.getId(), siteId, today.minusDays(7)));
        work = workRepository.save(new Work(siteId, today, "1번 탱크 내부 배관 용접", null, WorkType.CONFINED,
                null, null, 2, userId));
        em.flush();
    }

    @Test
    @DisplayName("작업 요인·물질·배치 → 정기 평가 → 일일 평가(참고) → 위험요인 → TBM 이 저장되고 다시 읽힌다")
    void saveAndReload() {
        // 작업: 고위험 요인 2개 (밀폐 + 화기), 물질, 배치 인원
        factorRepository.save(new WorkRiskFactor(work.getId(), WorkType.CONFINED, DetectedBy.AGENT));
        factorRepository.save(new WorkRiskFactor(work.getId(), WorkType.HOT, DetectedBy.USER));
        materialRepository.save(new WorkMaterial(work.getId(), "108-88-3", "톨루엔", new BigDecimal("20.5"), ChemicalUnit.L));
        workWorkerRepository.save(WorkWorker.of(work, welder.getId(), WorkRole.LEADER));
        workWorkerRepository.save(WorkWorker.of(work, watcher.getId(), WorkRole.ATTENDANT));

        // 정기 평가 (구역 단위) → 승인
        RiskAssessment regular = RiskAssessment.periodic(siteId, AssessmentType.REGULAR, tank.getId(),
                "2026 저장탱크 정기 위험성평가", today.minusMonths(1), userId);
        regular = assessmentRepository.saveAndFlush(regular);
        regular.submit();
        regular.approve();

        // 일일 평가 (작업 단위, 정기 평가 참고) + 위험요인
        RiskAssessment daily = RiskAssessment.daily(siteId, work.getId(), today, userId);
        daily.referBase(regular.getId());
        daily.updateContent(null, null, today, RiskMethod.FREQ_SEVERITY, "v1", "{\"source\":\"agent\"}");
        daily = assessmentRepository.saveAndFlush(daily);
        itemRepository.save(new RiskAssessmentItem(daily.getId(), 1, "탱크 진입", "산소결핍", "ASPHYXIATION",
                3, 5, RiskLevel.HIGH, "환기 후 산소 측정, 감시인 배치", 1, 5));
        itemRepository.save(new RiskAssessmentItem(daily.getId(), 2, "배관 용접", "불티로 인한 화재", "FIRE",
                2, 4, RiskLevel.MEDIUM, "불티 방지포, 소화기 비치", null, null));

        // TBM: 작업 연결 + 참석자 + 실시 완료
        TbmRecord tbm = tbmRepository.saveAndFlush(new TbmRecord(siteId, today, team.getId(), tank.getId(), userId));
        tbm.updateContent(team.getId(), tank.getId(), today, "김용접", "[\"산소 농도 확인\",\"불티 방지포 설치\"]", "이상 없음");
        tbmWorkRepository.save(TbmWork.of(tbm, work));
        attendeeRepository.save(TbmAttendee.of(tbm, welder.getId(), true));
        attendeeRepository.save(TbmAttendee.of(tbm, watcher.getId(), null));
        tbm.complete(LocalDateTime.now().withNano(0), 2);

        em.flush();
        em.clear();

        // 다시 읽기
        assertThat(factorRepository.findByWorkId(work.getId())).extracting(WorkRiskFactor::getFactor)
                .containsExactlyInAnyOrder(WorkType.CONFINED, WorkType.HOT);
        assertThat(materialRepository.findByWorkIdOrderByIdAsc(work.getId()).get(0).getQuantity()).isEqualByComparingTo("20.5");
        assertThat(workWorkerRepository.existsByWorkIdAndRoleInWork(work.getId(), WorkRole.ATTENDANT)).isTrue();
        assertThat(workWorkerRepository.existsByWorkIdAndRoleInWork(work.getId(), WorkRole.FIRE_WATCHER)).isFalse();

        RiskAssessment latest = assessmentRepository.findTopByWorkIdOrderByRevisionDesc(work.getId()).orElseThrow();
        assertThat(latest.getBaseAssessmentId()).isEqualTo(regular.getId());
        assertThat(latest.getFormData()).contains("agent");
        assertThat(assessmentRepository.findBySiteIdAndZoneIdAndAssessmentTypeInAndStatusOrderByAssessDateDesc(
                siteId, tank.getId(), List.of(AssessmentType.REGULAR, AssessmentType.ADHOC), RiskStatus.APPROVED))
                .extracting(RiskAssessment::getId).containsExactly(regular.getId());

        List<RiskAssessmentItem> items = itemRepository.findByAssessmentIdOrderBySeqAsc(latest.getId());
        assertThat(items).extracting(RiskAssessmentItem::getRiskScore).containsExactly(15, 8);   // DB 생성 컬럼
        assertThat(items.get(0).getResidualScore()).isEqualTo(5);
        assertThat(items.get(1).getResidualScore()).isNull();

        TbmRecord reloaded = tbmRepository.findById(tbm.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TbmStatus.DONE);
        assertThat(reloaded.getEducationPoints()).contains("산소 농도 확인");
        assertThat(tbmRepository.existsBySiteIdAndTbmDate(siteId, today)).isTrue();
        assertThat(tbmWorkRepository.existsByWorkIdAndTbmDate(work.getId(), today)).isTrue();
        assertThat(attendeeRepository.countByTbmId(tbm.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("반려 후 새 판: revision 2 가 생기고 이전 판은 SUPERSEDED")
    void newRevision() {
        RiskAssessment first = assessmentRepository.saveAndFlush(RiskAssessment.daily(siteId, work.getId(), today, userId));
        first.submit();
        first.reject();
        RiskAssessment second = assessmentRepository.save(first.newRevision(userId));
        first.supersede();
        em.flush();
        em.clear();

        assertThat(assessmentRepository.findByWorkIdOrderByRevisionDesc(work.getId()))
                .extracting(RiskAssessment::getRevision, RiskAssessment::getStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2, RiskStatus.DRAFT),
                        org.assertj.core.groups.Tuple.tuple(1, RiskStatus.SUPERSEDED));
        assertThat(second.getId()).isNotNull();
    }

    @Test
    @DisplayName("TBM 날짜를 바꾸면 tbm_works 날짜도 DB가 같이 바꾼다")
    void tbmDateCascade() {
        TbmRecord tbm = tbmRepository.saveAndFlush(new TbmRecord(siteId, today, null, null, userId));
        tbmWorkRepository.saveAndFlush(TbmWork.of(tbm, work));
        tbm.updateContent(null, null, today.plusDays(1), null, null, null);
        em.flush();

        Object date = em.createNativeQuery("SELECT CAST(tbm_date AS CHAR) FROM tbm_works WHERE tbm_id = ?1")
                .setParameter(1, tbm.getId()).getSingleResult();
        assertThat(date.toString()).isEqualTo(today.plusDays(1).toString());
    }

    // ---- DB 잠금 (트리거·외래키). 엔티티 검사를 건너뛴 경우에도 DB가 막는지 ----

    @Test
    @DisplayName("제출된 위험성평가에는 위험요인을 추가할 수 없다 (trg_rai_lock_ins)")
    void riskItemsLockedAfterSubmit() {
        RiskAssessment a = assessmentRepository.saveAndFlush(RiskAssessment.daily(siteId, work.getId(), today, userId));
        a.submit();
        em.flush();

        assertThatThrownBy(() -> {
            itemRepository.save(new RiskAssessmentItem(a.getId(), 1, "진입", "산소결핍", null,
                    3, 5, RiskLevel.HIGH, "환기", null, null));
            em.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("승인된 TBM에는 참석자를 추가할 수 없다 (trg_ta_lock_ins)")
    void tbmLockedAfterApproval() {
        TbmRecord tbm = tbmRepository.saveAndFlush(new TbmRecord(siteId, today, null, null, userId));
        tbm.complete(LocalDateTime.now().withNano(0), 1);
        tbm.approve();
        em.flush();

        assertThatThrownBy(() -> {
            attendeeRepository.save(TbmAttendee.of(tbm, welder.getId(), true));
            em.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("승인된 작업에는 물질을 추가할 수 없다 (trg_wm_lock_ins)")
    void materialsLockedAfterWorkApproved() {
        em.createNativeQuery("UPDATE works SET status = 'APPROVED' WHERE id = ?1")
                .setParameter(1, work.getId()).executeUpdate();

        assertThatThrownBy(() -> {
            materialRepository.save(new WorkMaterial(work.getId(), null, "아세톤", null, null));
            em.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("현장에 배정되지 않은 작업자는 작업에 배치할 수 없다 (fk_ww_worker)")
    void workWorkerMustBeAssignedToSite() {
        Worker outsider = workerRepository.saveAndFlush(new Worker(null, "외부인", null, null, null));

        assertThatThrownBy(() -> {
            workWorkerRepository.save(WorkWorker.of(work, outsider.getId(), WorkRole.WORKER));
            em.flush();
        }).isInstanceOf(RuntimeException.class);
    }
}
