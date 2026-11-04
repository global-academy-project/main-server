package com.example.backend.dailylog.service;

import com.example.backend.approval.entity.ApprovalStatus;
import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.service.ApprovalService;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.dailylog.dto.DailyLogResponse;
import com.example.backend.dailylog.dto.UpdateDailyLogRequest;
import com.example.backend.dailylog.entity.DailyLogStatus;
import com.example.backend.preview.service.PreviewService;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMember;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.entity.SiteRole;
import com.example.backend.site.repository.SiteMemberRepository;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.user.entity.EmailVerifiedVia;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserRole;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.work.dto.CreateWorkRequest;
import com.example.backend.work.service.WorkService;
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

/** 일일 안전일지: 초안(서버 집계) → 요약 수정 → 승인 요청 → 회수·반려·승인 (실제 DB, 끝나면 롤백) */
@SpringBootTest
@Transactional
@DisplayName("일일 안전일지 API (실제 DB)")
class DailyLogDbTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);

    @Autowired DailyLogService service;
    @Autowired ApprovalService approvalService;
    @Autowired PreviewService previewService;
    @Autowired WorkService workService;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired SiteMemberRepository siteMemberRepository;

    private Long adminId;
    private Long siteId;
    private Long writer;
    private Long stranger;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        adminId = userRepository.save(User.bootstrapAdmin("dl-admin-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("일지현장-" + tag, SiteMode.GUIDE)).getId();
        writer = member("작성자", SiteRole.WRITER);
        stranger = member("다른현장", null);
        workService.create(writer, new CreateWorkRequest(siteId, DAY, "R-201 배관 용접", null, WorkType.HOT, null, null, null));
    }

    private Long member(String name, SiteRole role) {
        User u = new User("dl-" + UUID.randomUUID() + "@example.com", "HASH", name);
        u.verifyEmail(EmailVerifiedVia.ADMIN, LocalDateTime.now());
        u.approve(UserRole.MEMBER);
        Long id = userRepository.save(u).getId();
        if (role != null) {
            siteMemberRepository.save(new SiteMember(id, siteId, role, adminId));
        }
        return id;
    }

    @Test
    @DisplayName("초안: 그날 기록 집계 + 형식 요약, 다시 만들면 같은 안전일지를 덮어씀, 미래 날짜·다른 현장은 막음")
    void draft() {
        DailyLogResponse d = service.draft(writer, siteId, DAY);
        assertThat(d.status()).isEqualTo(DailyLogStatus.DRAFT);
        assertThat(d.date()).isEqualTo(DAY);
        assertThat(d.summary()).startsWith("11월 2일 작업 1건").contains("작업허가 0건");
        assertThat(d.stats().path("works").path("total").asInt()).isEqualTo(1);
        assertThat(d.stats().path("permits").path("total").asInt()).isZero();
        assertThat(d.stats().has("incomplete")).isTrue();
        assertThat(d.stats().path("incomplete").has("DAILY_LOG_MISSING")).isFalse();
        assertThat(d.checks().isArray()).isTrue();
        for (int i = 0; i < d.checks().size(); i++) {
            assertThat(d.checks().get(i).path("type").asString()).isNotEqualTo("DAILY_LOG_MISSING");
            assertThat(d.checks().get(i).path("count").asInt()).isPositive();
        }
        assertThat(d.evidence().isArray()).isTrue();
        assertThat(d.writerName()).isEqualTo("작성자");
        assertThat(d.canSubmit()).isTrue();

        DailyLogResponse again = service.draft(writer, siteId, DAY);
        assertThat(again.dailyLogId()).isEqualTo(d.dailyLogId());
        assertThat(service.list(writer, siteId, DAY, DAY)).hasSize(1);

        expectCode(() -> service.draft(writer, siteId, LocalDate.now().plusDays(1)), ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.draft(stranger, siteId, DAY), ErrorCode.SITE_NOT_ALLOWED);
        expectCode(() -> service.get(stranger, d.dailyLogId()), ErrorCode.SITE_NOT_ALLOWED);
        expectCode(() -> service.list(writer, siteId, DAY, DAY.minusDays(1)), ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("요약 수정(버전 확인) → 빈 요약은 승인 요청 400 → 승인 요청 → 회수·반려는 작성 중으로, 승인 후 수정·재생성 409")
    void editSubmitApprove() {
        DailyLogResponse d = service.draft(writer, siteId, DAY);
        expectCode(() -> service.update(writer, d.dailyLogId(), new UpdateDailyLogRequest(d.lockVersion() + 1, "x")),
                ErrorCode.CONFLICT_MODIFIED);

        DailyLogResponse emptied = service.update(writer, d.dailyLogId(), new UpdateDailyLogRequest(d.lockVersion(), "  "));
        assertThat(emptied.summary()).isNull();
        assertThat(emptied.canSubmit()).isFalse();
        expectCode(() -> service.submit(writer, d.dailyLogId(), emptied.lockVersion()), ErrorCode.INVALID_REQUEST);

        DailyLogResponse edited = service.update(writer, d.dailyLogId(),
                new UpdateDailyLogRequest(emptied.lockVersion(), "오늘 화기작업 1건, TBM 미실시 1건"));
        assertThat(edited.summary()).isEqualTo("오늘 화기작업 1건, TBM 미실시 1건");
        assertThat(edited.stats().path("works").path("total").asInt()).isEqualTo(1);   // 집계는 그대로

        DailyLogResponse submitted = service.submit(writer, d.dailyLogId(), edited.lockVersion());
        assertThat(submitted.status()).isEqualTo(DailyLogStatus.PENDING);
        assertThat(submitted.approvalId()).isNotNull();
        assertThat(submitted.approvalStatus()).isEqualTo(ApprovalStatus.PENDING);
        expectCode(() -> service.update(writer, d.dailyLogId(), new UpdateDailyLogRequest(submitted.lockVersion(), "y")),
                ErrorCode.INVALID_STATE);
        expectCode(() -> service.draft(writer, siteId, DAY), ErrorCode.INVALID_STATE);
        assertThat(previewService.ofTarget(writer, ApprovalTarget.DAILY_LOG, d.dailyLogId()).targetType()).isEqualTo("DAILY_LOG");

        // 회수 → 작성 중
        approvalService.cancel(writer, submitted.approvalId(), null);
        DailyLogResponse canceled = service.get(writer, d.dailyLogId());
        assertThat(canceled.status()).isEqualTo(DailyLogStatus.DRAFT);

        // 반려 → 작성 중
        DailyLogResponse again = service.submit(writer, d.dailyLogId(), canceled.lockVersion());
        approvalService.reject(adminId, again.approvalId(), "미조치 개선조치 확인", null);
        DailyLogResponse rejected = service.get(writer, d.dailyLogId());
        assertThat(rejected.status()).isEqualTo(DailyLogStatus.DRAFT);
        assertThat(rejected.approvalStatus()).isEqualTo(ApprovalStatus.REJECTED);

        // 승인 → 수정·재생성 막힘
        DailyLogResponse third = service.submit(writer, d.dailyLogId(), rejected.lockVersion());
        approvalService.approve(adminId, third.approvalId(), null);
        DailyLogResponse approved = service.get(writer, d.dailyLogId());
        assertThat(approved.status()).isEqualTo(DailyLogStatus.APPROVED);
        assertThat(approved.approvalStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approved.canSubmit()).isFalse();
        expectCode(() -> service.update(writer, d.dailyLogId(), new UpdateDailyLogRequest(approved.lockVersion(), "z")),
                ErrorCode.INVALID_STATE);
        expectCode(() -> service.draft(writer, siteId, DAY), ErrorCode.INVALID_STATE);
    }
}
