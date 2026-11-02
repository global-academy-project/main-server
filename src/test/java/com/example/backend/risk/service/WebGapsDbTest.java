package com.example.backend.risk.service;

import com.example.backend.approval.entity.ApprovalStatus;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.risk.dto.RiskAssessmentResponse;
import com.example.backend.risk.dto.SaveRiskAssessmentRequest;
import com.example.backend.risk.entity.AssessmentType;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.site.dto.ZoneRequest;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.site.service.ZoneService;
import com.example.backend.tbm.dto.CompleteTbmRequest;
import com.example.backend.tbm.dto.SaveTbmRequest;
import com.example.backend.tbm.dto.TbmResponse;
import com.example.backend.tbm.entity.TbmStatus;
import com.example.backend.tbm.service.TbmCommandService;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.work.dto.CreateWorkRequest;
import com.example.backend.work.dto.WorkListItem;
import com.example.backend.work.dto.WorkResponse;
import com.example.backend.work.service.WorkService;
import com.example.backend.worker.dto.SiteWorkerRequest;
import com.example.backend.worker.dto.SiteWorkerResponse;
import com.example.backend.worker.dto.TeamRequest;
import com.example.backend.worker.entity.EmploymentType;
import com.example.backend.worker.entity.TeamType;
import com.example.backend.worker.repository.WorkerRepository;
import com.example.backend.worker.service.SiteWorkerService;
import com.example.backend.worker.service.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

/** 웹 연결 현황 v3.1.0 에서 나온 서버 보완 (실제 DB, 끝나면 롤백) */
@SpringBootTest
@Transactional
@DisplayName("웹 보완: lockVersion·TBM 팀/구역/결재 상태·작업 구역·작업자 연락처 (실제 DB)")
class WebGapsDbTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);

    @Autowired RiskAssessmentService riskService;
    @Autowired TbmCommandService tbmService;
    @Autowired WorkService workService;
    @Autowired ZoneService zoneService;
    @Autowired TeamService teamService;
    @Autowired SiteWorkerService workerService;
    @Autowired WorkerRepository workerRepository;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;

    private String tag;
    private Long userId;
    private Long siteId;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        userId = userRepository.save(User.bootstrapAdmin("gap-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("보완현장-" + tag, SiteMode.GUIDE)).getId();   // 안내 모드: 자기 승인 가능
    }

    private Long work(Long zoneId) {
        return workService.create(userId, new CreateWorkRequest(siteId, DAY, "R-201 배관 교체", null, WorkType.HOT,
                DAY.atTime(13, 0), DAY.atTime(15, 0), 3, zoneId)).workId();
    }

    private Long zone(Long site, String code) {
        return zoneService.create(userId, site, new ZoneRequest(code, "반응기 하부", null, false, null, null)).zoneId();
    }

    private Long worker(String name) {
        return workerService.create(userId, siteId, new SiteWorkerRequest(name, "010-1234-5678", "배관공",
                EmploymentType.DIRECT, null, DAY.minusDays(30), null)).workerId();
    }

    private SaveRiskAssessmentRequest risk(Long workId, Integer lockVersion, String hazard) {
        return new SaveRiskAssessmentRequest(siteId, workId, AssessmentType.DAILY, DAY, null,
                List.of(new SaveRiskAssessmentRequest.Item(null, "절단", hazard, null, 2, 2, null, "대책", null, null)),
                lockVersion);
    }

    private SaveTbmRequest tbm(Long teamId, Long zoneId, List<Long> works, List<Long> workers, Integer lockVersion) {
        return new SaveTbmRequest(DAY, teamId, zoneId, "이반장", List.of("가스측정 결과 확인"), null, works,
                workers.stream().map(w -> new SaveTbmRequest.Attendee(w, true)).toList(), lockVersion);
    }

    // ------------------------------------------------------------------ lockVersion

    @Test
    @DisplayName("위험성평가: 위험요인만 바꿔도 lockVersion 이 오르고, 예전 버전으로 다시 보내면 409")
    void riskItemsOnlyBumpsVersion() {
        RiskAssessmentResponse r = riskService.create(userId, risk(work(null), null, "불티 착화"));

        RiskAssessmentResponse first = riskService.update(userId, r.riskAssessmentId(), risk(r.workId(), r.lockVersion(), "베임"));

        assertThat(first.lockVersion()).isEqualTo(r.lockVersion() + 1);
        expectCode(() -> riskService.update(userId, r.riskAssessmentId(), risk(r.workId(), r.lockVersion(), "끼임")),
                ErrorCode.CONFLICT_MODIFIED);
        RiskAssessmentResponse second = riskService.update(userId, r.riskAssessmentId(),
                risk(r.workId(), first.lockVersion(), "끼임"));
        assertThat(second.lockVersion()).isEqualTo(first.lockVersion() + 1);
    }

    @Test
    @DisplayName("TBM 전체 수정: 내용은 같고 참석자만 바꿔도 lockVersion 이 오르고, 예전 버전은 409")
    void tbmLinksOnlyBumpsVersion() {
        Long workId = work(null);
        Long w1 = worker("김작업");
        Long w2 = worker("박작업");
        TbmResponse t = tbmService.create(userId, siteId, tbm(null, null, List.of(workId), List.of(w1), null));

        TbmResponse updated = tbmService.update(userId, t.tbmId(), tbm(null, null, List.of(workId), List.of(w1, w2),
                t.lockVersion()));

        assertThat(updated.lockVersion()).isEqualTo(t.lockVersion() + 1);
        assertThat(updated.attendees()).hasSize(2);
        expectCode(() -> tbmService.update(userId, t.tbmId(), tbm(null, null, List.of(workId), List.of(w2), t.lockVersion())),
                ErrorCode.CONFLICT_MODIFIED);
    }

    // ------------------------------------------------------------------ TBM·위험성평가 응답

    @Test
    @DisplayName("TBM 응답: 팀·구역 id 와 이름, 결재 상태 (없으면 null, 확인 요청 중이면 PENDING 이고 status 는 DONE)")
    void tbmTeamZoneAndApproval() {
        Long teamId = teamService.create(userId, new TeamRequest("배관팀-" + tag, TeamType.INTERNAL, null, null)).teamId();
        Long zoneId = zone(siteId, "R-201");
        TbmResponse t = tbmService.create(userId, siteId, tbm(teamId, zoneId, List.of(work(null)), List.of(), null));

        assertThat(t.teamId()).isEqualTo(teamId);
        assertThat(t.teamName()).isEqualTo("배관팀-" + tag);
        assertThat(t.zoneId()).isEqualTo(zoneId);
        assertThat(t.zoneName()).isEqualTo("반응기 하부");
        assertThat(t.approvalId()).isNull();
        assertThat(t.approvalStatus()).isNull();

        TbmResponse done = tbmService.complete(userId, t.tbmId(), new CompleteTbmRequest(t.lockVersion(), DAY.atTime(8, 30), 3));
        TbmResponse submitted = tbmService.submit(userId, t.tbmId());

        assertThat(submitted.status()).isEqualTo(TbmStatus.DONE);
        assertThat(submitted.approvalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(submitted.approvalId()).isNotNull();
        expectCode(() -> tbmService.complete(userId, t.tbmId(), new CompleteTbmRequest(done.lockVersion(), DAY.atTime(9, 0), 3)),
                ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("위험성평가 응답: 결재 상태 (승인 요청 전 null, 요청 후 PENDING)")
    void riskApproval() {
        RiskAssessmentResponse r = riskService.create(userId, risk(work(null), null, "불티 착화"));
        assertThat(r.approvalStatus()).isNull();

        RiskAssessmentResponse submitted = riskService.submit(userId, r.riskAssessmentId());

        assertThat(submitted.approvalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(submitted.approvalId()).isNotNull();
    }

    // ------------------------------------------------------------------ 작업 구역

    @Test
    @DisplayName("작업 등록 구역: 같은 현장의 사용 중 구역만 (응답·목록에 zoneId), 다른 현장·사용 중지 구역은 400")
    void workZone() {
        Long zoneId = zone(siteId, "R-201");

        Long workId = work(zoneId);

        WorkResponse w = workService.get(userId, workId);
        assertThat(w.zoneId()).isEqualTo(zoneId);
        assertThat(workService.list(userId, siteId, DAY, DAY)).extracting(WorkListItem::zoneId).containsExactly(zoneId);

        Long otherSite = siteRepository.save(new Site("다른현장-" + tag, SiteMode.GUIDE)).getId();
        Long otherZone = zone(otherSite, "X-1");
        expectCode(() -> work(otherZone), ErrorCode.INVALID_REQUEST);
        zoneService.update(userId, siteId, zoneId, new ZoneRequest("R-201", "반응기 하부", null, false, null, false));
        expectCode(() -> work(zoneId), ErrorCode.INVALID_REQUEST);
        assertThat(workService.get(userId, work(null)).zoneId()).isNull();
    }

    // ------------------------------------------------------------------ 작업자 연락처

    @Test
    @DisplayName("작업자 수정: 연락처를 안 보내면(null) 그대로, 빈 문자열이면 지움")
    void workerPhone() {
        Long id = worker("김작업");
        SiteWorkerResponse before = workerService.list(userId, siteId).get(0);

        SiteWorkerResponse kept = workerService.update(userId, siteId, id, new SiteWorkerRequest("김작업", null, "용접공",
                EmploymentType.DIRECT, null, null, before.lockVersion()));
        assertThat(workerRepository.findById(id).orElseThrow().getPhone()).isEqualTo("01012345678");

        workerService.update(userId, siteId, id, new SiteWorkerRequest("김작업", "", "용접공",
                EmploymentType.DIRECT, null, null, kept.lockVersion()));
        assertThat(workerRepository.findById(id).orElseThrow().getPhone()).isNull();
    }
}
