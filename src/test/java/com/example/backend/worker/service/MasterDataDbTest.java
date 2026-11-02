package com.example.backend.worker.service;

import com.example.backend.common.exception.ErrorCode;
import com.example.backend.site.dto.ZoneRequest;
import com.example.backend.site.dto.ZoneResponse;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMember;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.entity.SiteRole;
import com.example.backend.site.repository.SiteMemberRepository;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.site.service.ZoneService;
import com.example.backend.user.entity.EmailVerifiedVia;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserRole;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.worker.dto.SiteWorkerRequest;
import com.example.backend.worker.dto.SiteWorkerResponse;
import com.example.backend.worker.dto.TeamRequest;
import com.example.backend.worker.dto.TeamResponse;
import com.example.backend.worker.entity.EmploymentType;
import com.example.backend.worker.entity.TeamType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

/** 기준 정보: 구역·팀·현장 작업자 (실제 DB, 끝나면 롤백). 현장 관리자만 바꿀 수 있고 작성자는 조회만 */
@SpringBootTest
@Transactional
@DisplayName("기준 정보 API (실제 DB)")
class MasterDataDbTest {

    @Autowired ZoneService zoneService;
    @Autowired TeamService teamService;
    @Autowired SiteWorkerService workerService;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired SiteMemberRepository siteMemberRepository;

    private String tag;
    private Long adminId;
    private Long siteId;
    private Long siteAdmin;
    private Long writer;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        adminId = userRepository.save(User.bootstrapAdmin("md-admin-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("기준현장-" + tag, SiteMode.VERIFY)).getId();
        siteAdmin = member(siteId, "현장관리자", SiteRole.SITE_ADMIN);
        writer = member(siteId, "작성자", SiteRole.WRITER);
    }

    private Long member(Long site, String name, SiteRole role) {
        User u = new User("md-" + UUID.randomUUID() + "@example.com", "HASH", name);
        u.verifyEmail(EmailVerifiedVia.ADMIN, LocalDateTime.now());
        u.approve(UserRole.MEMBER);
        Long id = userRepository.save(u).getId();
        siteMemberRepository.save(new SiteMember(id, site, role, adminId));
        return id;
    }

    private SiteWorkerRequest worker(String name, Long teamId, Integer lockVersion) {
        return new SiteWorkerRequest(name, "010-1234-5678", "배관공", EmploymentType.CONTRACTOR, teamId,
                LocalDate.of(2026, 11, 1), lockVersion);
    }

    // ------------------------------------------------------------------ 구역

    @Test
    @DisplayName("구역: 작성자는 조회만, 현장 관리자가 등록·수정. 같은 코드 409, 사용 중지 구역은 기본 목록에서 빠짐")
    void zones() {
        expectCode(() -> zoneService.create(writer, siteId, new ZoneRequest("R-201", "반응기 하부", null, true, null, null)),
                ErrorCode.FORBIDDEN);
        ZoneResponse z = zoneService.create(siteAdmin, siteId, new ZoneRequest(" R-201 ", "반응기 하부", "PROCESS", true,
                "톨루엔 취급", null));
        assertThat(z.code()).isEqualTo("R-201");
        expectCode(() -> zoneService.create(siteAdmin, siteId, new ZoneRequest("R-201", "중복", null, false, null, null)),
                ErrorCode.INVALID_STATE);
        assertThat(zoneService.list(writer, siteId, false)).extracting(ZoneResponse::zoneId).containsExactly(z.zoneId());

        zoneService.update(siteAdmin, siteId, z.zoneId(), new ZoneRequest("R-201", "반응기 하부", "PROCESS", true, null, false));
        assertThat(zoneService.list(writer, siteId, false)).isEmpty();
        assertThat(zoneService.list(writer, siteId, true)).singleElement().satisfies(x -> assertThat(x.active()).isFalse());

        Long otherSite = siteRepository.save(new Site("다른현장-" + tag, SiteMode.VERIFY)).getId();
        expectCode(() -> zoneService.update(adminId, otherSite, z.zoneId(), new ZoneRequest("X", "x", null, false, null, null)),
                ErrorCode.NOT_FOUND);   // 다른 현장 구역은 못 고침 (회사 관리자도 현장 경로를 맞춰야 함)
    }

    // ------------------------------------------------------------------ 팀

    @Test
    @DisplayName("팀: 이름·사업자번호 중복 409, 사내 팀에 사업자번호 400, 사용 중지 팀은 기본 목록에서 빠짐")
    void teams() {
        String bizNo = String.valueOf(1_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 8_999_999_999L));
        String dashed = bizNo.substring(0, 3) + "-" + bizNo.substring(3, 5) + "-" + bizNo.substring(5);
        TeamResponse t = teamService.create(adminId, new TeamRequest("협력업체-" + tag, TeamType.EXTERNAL, dashed, null));
        assertThat(t.businessNo()).isEqualTo(bizNo);   // 하이픈 빼고 저장
        expectCode(() -> teamService.create(adminId, new TeamRequest("협력업체-" + tag, TeamType.EXTERNAL, null, null)),
                ErrorCode.INVALID_STATE);
        expectCode(() -> teamService.create(adminId, new TeamRequest("사내-" + tag, TeamType.INTERNAL, "1234567891", null)),
                ErrorCode.INVALID_REQUEST);

        teamService.update(adminId, t.teamId(), new TeamRequest("협력업체-" + tag, TeamType.EXTERNAL, bizNo, false));
        assertThat(teamService.list(false)).extracting(TeamResponse::teamId).doesNotContain(t.teamId());
        assertThat(teamService.list(true)).extracting(TeamResponse::teamId).contains(t.teamId());
    }

    // ------------------------------------------------------------------ 작업자

    @Test
    @DisplayName("작업자 등록: 현장 관리자만, 팀 이름 포함, 연락처는 응답에 없음. 작성자는 목록만")
    void createWorker() {
        Long teamId = teamService.create(adminId, new TeamRequest("배관팀-" + tag, TeamType.INTERNAL, null, null)).teamId();
        expectCode(() -> workerService.create(writer, siteId, worker("김작업", teamId, null)), ErrorCode.FORBIDDEN);

        SiteWorkerResponse w = workerService.create(siteAdmin, siteId, worker("김작업", teamId, null));

        assertThat(w.teamName()).isEqualTo("배관팀-" + tag);
        assertThat(w.assignedFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(workerService.list(writer, siteId)).extracting(SiteWorkerResponse::workerId).containsExactly(w.workerId());
        assertThat(SiteWorkerResponse.class.getRecordComponents()).extracting(c -> c.getName()).doesNotContain("phone");
        expectCode(() -> workerService.create(siteAdmin, siteId, worker("박작업", 999_999_999L, null)), ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("작업자 수정: lockVersion 확인, 회사 관리자도 가능")
    void updateWorker() {
        SiteWorkerResponse w = workerService.create(siteAdmin, siteId, worker("김작업", null, null));
        expectCode(() -> workerService.update(siteAdmin, siteId, w.workerId(), worker("김작업", null, w.lockVersion() + 1)),
                ErrorCode.CONFLICT_MODIFIED);
        SiteWorkerResponse updated = workerService.update(adminId, siteId, w.workerId(),
                new SiteWorkerRequest("김작업", null, "용접공", EmploymentType.DIRECT, null, null, w.lockVersion()));
        assertThat(updated.jobType()).isEqualTo("용접공");
        assertThat(updated.lockVersion()).isGreaterThan(w.lockVersion());
    }

    @Test
    @DisplayName("현장 떠남 → 목록에서 빠짐 → 다시 배치. 배치 중에 또 배치하면 409, 다른 현장에도 배치 가능")
    void leaveAndAssign() {
        SiteWorkerResponse w = workerService.create(siteAdmin, siteId, worker("김작업", null, null));

        workerService.leave(siteAdmin, siteId, w.workerId(), LocalDate.of(2026, 11, 20));
        assertThat(workerService.list(writer, siteId)).isEmpty();
        expectCode(() -> workerService.leave(siteAdmin, siteId, w.workerId(), null), ErrorCode.NOT_FOUND);

        SiteWorkerResponse back = workerService.assign(siteAdmin, siteId, w.workerId(), LocalDate.of(2026, 12, 1));
        assertThat(back.assignedFrom()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(back.assignedTo()).isNull();
        expectCode(() -> workerService.assign(siteAdmin, siteId, w.workerId(), null), ErrorCode.INVALID_STATE);

        Long otherSite = siteRepository.save(new Site("둘째현장-" + tag, SiteMode.GUIDE)).getId();
        workerService.assign(adminId, otherSite, w.workerId(), null);
        assertThat(workerService.list(adminId, otherSite)).extracting(SiteWorkerResponse::workerId).containsExactly(w.workerId());
    }
}
