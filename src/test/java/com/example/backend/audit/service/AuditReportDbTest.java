package com.example.backend.audit.service;

import com.example.backend.audit.dto.AuditReportResponse;
import com.example.backend.audit.dto.CreateAuditReportRequest;
import com.example.backend.audit.dto.UpdateAuditReportRequest;
import com.example.backend.audit.entity.AuditReportStatus;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.corrective.dto.SaveCorrectiveActionRequest;
import com.example.backend.corrective.entity.ActionSource;
import com.example.backend.corrective.service.CorrectiveActionService;
import com.example.backend.history.entity.AccessAction;
import com.example.backend.history.entity.AccessResource;
import com.example.backend.history.repository.RecordAccessLogRepository;
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

/** 감사보고서: 집계 + AI 요약(가짜 AI 서버) → 고치기 → 삭제, 권한 (실제 DB, 끝나면 롤백) */
@SpringBootTest
@Transactional
@DisplayName("감사보고서 API (실제 DB, 가짜 AI)")
class AuditReportDbTest {

    @Autowired AuditReportService service;
    @Autowired CorrectiveActionService actionService;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired SiteMemberRepository siteMemberRepository;
    @Autowired RecordAccessLogRepository accessLogRepository;

    private Long adminId;
    private Long writer;
    private Long siteId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        adminId = userRepository.save(User.bootstrapAdmin("ar-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("감사현장-" + tag, SiteMode.VERIFY)).getId();
        User u = new User("ar-w-" + tag + "@example.com", "HASH", "작성자");
        u.verifyEmail(EmailVerifiedVia.ADMIN, LocalDateTime.now());
        u.approve(UserRole.MEMBER);
        writer = userRepository.save(u).getId();
        siteMemberRepository.save(new SiteMember(writer, siteId, SiteRole.WRITER, adminId));
    }

    @Test
    @DisplayName("만들기: 고른 항목을 화면 순서로 집계해 저장(요약 중) → AI 요약·검증 → 고치기·숨김 삭제")
    void createEditDelete() {
        actionService.create(writer, siteId, new SaveCorrectiveActionRequest(ActionSource.OTHER, null, "소화기 비치",
                null, writer, LocalDate.now().plusDays(3), null));
        LocalDate today = LocalDate.now();
        AuditReportResponse created = service.create(adminId, siteId, new CreateAuditReportRequest(today.minusDays(7), today,
                List.of("corrective_action_rate", "risk_assessment_rate")));
        assertThat(created.status()).isEqualTo(AuditReportStatus.GENERATING);
        assertThat(created.summaryRetryable()).isFalse();   // 막 시작한 요약은 다시 하기 불가
        service.get(adminId, siteId, created.reportId());   // 요약 중 화면이 다시 조회 (만들 때 남긴 로그가 있어 열람 로그 안 남김)
        service.get(adminId, siteId, created.reportId());
        assertThat(views(created.reportId())).isZero();
        expectCode(() -> service.update(adminId, siteId, created.reportId(), new UpdateAuditReportRequest("x", null, List.of(),
                created.lockVersion())), ErrorCode.INVALID_STATE);   // 요약 중에는 못 고침
        expectCode(() -> service.retrySummary(adminId, siteId, created.reportId()), ErrorCode.INVALID_STATE);

        service.runSummary(created.reportId());   // 커밋 뒤 리스너가 하는 일 (테스트는 롤백이라 직접)
        AuditReportResponse r = service.get(adminId, siteId, created.reportId());

        assertThat(r.status()).isEqualTo(AuditReportStatus.DRAFT);
        assertThat(r.summaryRetryable()).isFalse();
        assertThat(views(created.reportId())).isEqualTo(1);   // 요약이 끝난 뒤 연 것은 남긴다
        assertThat(r.statusLabel()).isEqualTo("초안");
        assertThat(r.queryTypes()).containsExactly("risk_assessment_rate", "corrective_action_rate");
        assertThat(r.items()).hasSize(2);
        assertThat(r.items().get(1).path("stats").path("actions").asInt()).isEqualTo(1);
        assertThat(r.items().get(1).path("summary").asString()).contains("연습 요약");
        assertThat(r.items().get(1).path("verification").path("status").asString()).isEqualTo("MATCH");

        AuditReportResponse edited = service.update(adminId, siteId, r.reportId(), new UpdateAuditReportRequest("11월 감사보고서",
                "전반적으로 양호", List.of(new UpdateAuditReportRequest.Item("risk_assessment_rate", "직접 고친 요약")), r.lockVersion()));
        assertThat(edited.title()).isEqualTo("11월 감사보고서");
        assertThat(edited.items().get(0).path("summary").asString()).isEqualTo("직접 고친 요약");
        assertThat(edited.items().get(0).path("stats").isObject()).isTrue();   // 집계는 그대로
        expectCode(() -> service.update(adminId, siteId, r.reportId(), new UpdateAuditReportRequest("x", null, List.of(),
                r.lockVersion())), ErrorCode.CONFLICT_MODIFIED);

        assertThat(service.list(adminId, siteId)).singleElement().satisfies(x -> assertThat(x.items()).isEmpty());
        service.delete(adminId, siteId, r.reportId());
        assertThat(service.list(adminId, siteId)).isEmpty();
        expectCode(() -> service.get(adminId, siteId, r.reportId()), ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("현장 관리자 미만은 쓸 수 없고, 없는 항목·중복·뒤집힌 기간·미래 기간은 거절")
    void guards() {
        LocalDate today = LocalDate.now();
        expectCode(() -> service.list(writer, siteId), ErrorCode.FORBIDDEN);
        expectCode(() -> service.create(adminId, siteId, new CreateAuditReportRequest(today, today, List.of("missing_tbm"))),
                ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.create(adminId, siteId, new CreateAuditReportRequest(today, today,
                List.of("blocked_records", "blocked_records"))), ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.create(adminId, siteId, new CreateAuditReportRequest(today, today.minusDays(1),
                List.of("blocked_records"))), ErrorCode.INVALID_REQUEST);
        // 미래 기간 (종료일이 오늘 이후)은 거절 (11/20, 화면에서 만들 때도)
        expectCode(() -> service.create(adminId, siteId, new CreateAuditReportRequest(today, today.plusDays(1),
                List.of("blocked_records"))), ErrorCode.INVALID_REQUEST);
    }

    private long views(Long reportId) {
        return accessLogRepository.findAll().stream()
                .filter(l -> l.getResourceType() == AccessResource.AUDIT_REPORT && reportId.equals(l.getResourceId())
                        && l.getAction() == AccessAction.VIEW && adminId.equals(l.getUserId()))
                .count();
    }
}
