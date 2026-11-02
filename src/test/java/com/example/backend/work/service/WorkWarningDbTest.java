package com.example.backend.work.service;

import com.example.backend.common.exception.ErrorCode;
import com.example.backend.nearmiss.dto.NearMissResponse;
import com.example.backend.nearmiss.dto.ReportNearMissRequest;
import com.example.backend.nearmiss.dto.SaveTagsRequest;
import com.example.backend.nearmiss.service.NearMissService;
import com.example.backend.notification.dto.NotificationItem;
import com.example.backend.notification.entity.NotificationType;
import com.example.backend.notification.service.NotificationService;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.site.dto.ZoneRequest;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMember;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.entity.SiteRole;
import com.example.backend.site.repository.SiteMemberRepository;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.site.service.ZoneService;
import com.example.backend.tag.entity.TagAxis;
import com.example.backend.tag.entity.TagSource;
import com.example.backend.user.entity.EmailVerifiedVia;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserRole;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.work.dto.CreateWorkRequest;
import com.example.backend.work.dto.WorkWarningResponse;
import com.example.backend.work.entity.WarningSeverity;
import com.example.backend.work.entity.WarningSource;
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

/** 같은 구역 아차사고 경고 (시나리오 C, 실제 DB, 끝나면 롤백) */
@SpringBootTest
@Transactional
@DisplayName("같은 구역 아차사고 경고 (실제 DB)")
class WorkWarningDbTest {

    @Autowired WorkWarningService warningService;
    @Autowired WorkService workService;
    @Autowired NearMissService nearMissService;
    @Autowired NotificationService notificationService;
    @Autowired ZoneService zoneService;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired SiteMemberRepository siteMemberRepository;

    private String tag;
    private Long adminId;
    private Long siteId;
    private Long writer;
    private Long reporter;
    private Long zoneA;
    private Long zoneB;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        adminId = userRepository.save(User.bootstrapAdmin("ww-admin-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("경고현장-" + tag, SiteMode.VERIFY)).getId();   // 경고 기간 기본 30일
        writer = member("작업작성자");
        reporter = member("보고자");
        zoneA = zoneService.create(adminId, siteId, new ZoneRequest("T-101", "저장탱크", null, true, null, null)).zoneId();
        zoneB = zoneService.create(adminId, siteId, new ZoneRequest("R-201", "반응기", null, false, null, null)).zoneId();
    }

    private Long member(String name) {
        User u = new User("ww-" + UUID.randomUUID() + "@example.com", "HASH", name);
        u.verifyEmail(EmailVerifiedVia.ADMIN, LocalDateTime.now());
        u.approve(UserRole.MEMBER);
        Long id = userRepository.save(u).getId();
        siteMemberRepository.save(new SiteMember(id, siteId, SiteRole.WRITER, adminId));
        return id;
    }

    private Long work(LocalDate date, Long zone) {
        return workService.create(writer, new CreateWorkRequest(siteId, date, "맨홀 주변 배관 교체", null, WorkType.GENERAL,
                null, null, 2, zone)).workId();
    }

    private NearMissResponse nearMiss(Long zone, LocalDateTime at) {
        return nearMissService.report(reporter, new ReportNearMissRequest(siteId, zone, null, "맨홀 주변 미끄러짐", at,
                "바닥 기름", "접근 제한"));
    }

    @Test
    @DisplayName("아차사고 보고 → 같은 구역의 오늘 이후 작업에만 경고, 작업 작성자에게 알림")
    void reportWarnsWorks() {
        LocalDate today = LocalDate.now();
        Long sameZone = work(today, zoneA);
        Long tomorrow = work(today.plusDays(1), zoneA);
        Long otherZone = work(today, zoneB);
        Long past = work(today.minusDays(1), zoneA);

        NearMissResponse n = nearMiss(zoneA, LocalDateTime.now().minusHours(1));

        assertThat(warningService.list(writer, sameZone)).singleElement().satisfies(w -> {
            assertThat(w.sourceType()).isEqualTo(WarningSource.NEAR_MISS);
            assertThat(w.nearMissId()).isEqualTo(n.nearMissId());
            assertThat(w.severity()).isEqualTo(WarningSeverity.WARN);
            assertThat(w.message()).contains("저장탱크").contains("맨홀 주변 미끄러짐").contains("접근 제한");
            assertThat(w.acknowledgedAt()).isNull();
        });
        assertThat(warningService.list(writer, tomorrow)).hasSize(1);
        assertThat(warningService.list(writer, otherZone)).isEmpty();
        assertThat(warningService.list(writer, past)).isEmpty();
        assertThat(notificationService.list(writer, siteId, false).items())
                .filteredOn(i -> i.type() == NotificationType.NEAR_MISS_WARNING)
                .extracting(NotificationItem::targetId).containsExactlyInAnyOrder(sameZone, tomorrow);
    }

    @Test
    @DisplayName("구역을 정해 작업 등록 → 그 구역 최근 아차사고(경고 기간 안)만 경고, 구역 없으면 경고 없음")
    void newWorkWarned() {
        NearMissResponse recent = nearMiss(zoneA, LocalDateTime.now().minusDays(3));
        nearMiss(zoneA, LocalDateTime.now().minusDays(40));   // 기본 30일 밖
        nearMiss(zoneB, LocalDateTime.now().minusDays(1));

        Long w = work(LocalDate.now(), zoneA);

        assertThat(warningService.list(writer, w)).extracting(WorkWarningResponse::nearMissId)
                .containsExactly(recent.nearMissId());
        assertThat(warningService.list(writer, work(LocalDate.now(), null))).isEmpty();
        assertThat(warningService.unacknowledged(w)).isEqualTo(1);
    }

    @Test
    @DisplayName("중상·사망 아차사고는 긴급 경고: 조치 내용 없이 확인하면 400, 확인은 한 번만(409)")
    void criticalAcknowledge() {
        NearMissResponse n = nearMiss(zoneA, LocalDateTime.now().minusDays(1));
        nearMissService.saveTags(reporter, n.nearMissId(), new SaveTagsRequest(n.lockVersion(), List.of(
                new SaveTagsRequest.Tag(TagAxis.SEVERITY, "SERIOUS", TagSource.USER, null))));
        Long w = work(LocalDate.now(), zoneA);
        WorkWarningResponse warning = warningService.list(writer, w).get(0);
        assertThat(warning.severity()).isEqualTo(WarningSeverity.CRITICAL);

        expectCode(() -> warningService.acknowledge(writer, warning.warningId(), " "), ErrorCode.INVALID_REQUEST);
        WorkWarningResponse acked = warningService.acknowledge(writer, warning.warningId(), "안전대 2중 체결, 바닥 흡착포");

        assertThat(acked.acknowledgedByName()).isEqualTo("작업작성자");
        assertThat(acked.ackNote()).isEqualTo("안전대 2중 체결, 바닥 흡착포");
        assertThat(warningService.unacknowledged(w)).isZero();
        expectCode(() -> warningService.acknowledge(writer, warning.warningId(), "다시"), ErrorCode.INVALID_STATE);
    }
}
