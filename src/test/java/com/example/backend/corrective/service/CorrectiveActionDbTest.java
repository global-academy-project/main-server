package com.example.backend.corrective.service;

import com.example.backend.common.exception.ErrorCode;
import com.example.backend.corrective.dto.CorrectiveActionResponse;
import com.example.backend.corrective.dto.CorrectiveActionStepRequest;
import com.example.backend.corrective.dto.SaveCorrectiveActionRequest;
import com.example.backend.corrective.entity.ActionSource;
import com.example.backend.corrective.entity.ActionStatus;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.repository.RecordHistoryRepository;
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

/** 개선조치: 등록 → 시작 → 완료 → 확인, 미흡·취소, 권한·출처·담당자 확인 (실제 DB, 끝나면 롤백) */
@SpringBootTest
@Transactional
@DisplayName("개선조치 API (실제 DB)")
class CorrectiveActionDbTest {

    @Autowired CorrectiveActionService service;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired SiteMemberRepository siteMemberRepository;
    @Autowired RecordHistoryRepository historyRepository;

    private Long siteId;
    private Long writer;
    private Long approver;
    private Long outsider;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long adminId = userRepository.save(User.bootstrapAdmin("ca-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("조치현장-" + tag, SiteMode.VERIFY)).getId();
        writer = member("ca-w-" + tag, "작성자", SiteRole.WRITER, adminId, siteId);
        approver = member("ca-a-" + tag, "승인자", SiteRole.APPROVER, adminId, siteId);
        Long otherSite = siteRepository.save(new Site("다른현장-" + tag, SiteMode.VERIFY)).getId();
        outsider = member("ca-o-" + tag, "외부인", SiteRole.WRITER, adminId, otherSite);
    }

    private Long member(String email, String name, SiteRole role, Long adminId, Long site) {
        User u = new User(email + "@example.com", "HASH", name);
        u.verifyEmail(EmailVerifiedVia.ADMIN, LocalDateTime.now());
        u.approve(UserRole.MEMBER);
        Long id = userRepository.save(u).getId();
        siteMemberRepository.save(new SiteMember(id, site, role, adminId));
        return id;
    }

    private SaveCorrectiveActionRequest req(Long assignee, Integer lockVersion) {
        return new SaveCorrectiveActionRequest(ActionSource.OTHER, null, "비계 발판 고정", "2단 발판 고정 핀 교체", assignee,
                LocalDate.of(2026, 11, 20), lockVersion);
    }

    @Test
    @DisplayName("등록 → 시작 → 완료(조치 내용 필수) → 확인. 담당자 이름·이력이 남는다")
    void fullFlow() {
        CorrectiveActionResponse a = service.create(writer, siteId, req(writer, null));
        assertThat(a.status()).isEqualTo(ActionStatus.OPEN);
        assertThat(a.assigneeName()).isEqualTo("작성자");
        assertThat(service.assignees(writer, siteId)).extracting(x -> x.userId()).contains(writer, approver);

        CorrectiveActionResponse started = service.start(writer, a.actionId(), new CorrectiveActionStepRequest(a.lockVersion(), null));
        assertThat(started.status()).isEqualTo(ActionStatus.IN_PROGRESS);
        expectCode(() -> service.complete(writer, a.actionId(), new CorrectiveActionStepRequest(started.lockVersion(), " ")),
                ErrorCode.INVALID_REQUEST);
        CorrectiveActionResponse done = service.complete(writer, a.actionId(),
                new CorrectiveActionStepRequest(started.lockVersion(), "고정 핀 교체 완료"));
        assertThat(done.status()).isEqualTo(ActionStatus.DONE);
        assertThat(done.completionNote()).isEqualTo("고정 핀 교체 완료");

        expectCode(() -> service.verify(writer, a.actionId(), new CorrectiveActionStepRequest(done.lockVersion(), null)),
                ErrorCode.FORBIDDEN);   // 작성자는 확인 불가
        CorrectiveActionResponse verified = service.verify(approver, a.actionId(), new CorrectiveActionStepRequest(done.lockVersion(), null));
        assertThat(verified.status()).isEqualTo(ActionStatus.VERIFIED);
        assertThat(verified.verifiedByName()).isEqualTo("승인자");
        assertThat(historyRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAscIdAsc(HistoryTarget.CORRECTIVE_ACTION, a.actionId()))
                .extracting(h -> h.getAction())
                .contains(HistoryAction.CREATE, HistoryAction.STATUS_CHANGE, HistoryAction.VERIFY);
    }

    @Test
    @DisplayName("검증 모드: 담당자 본인은 확인할 수 없다. 미흡이면 다시 조치 중")
    void verifyMode_selfVerifyForbidden_reopen() {
        CorrectiveActionResponse a = service.create(writer, siteId, req(approver, null));
        CorrectiveActionResponse s = service.start(approver, a.actionId(), new CorrectiveActionStepRequest(a.lockVersion(), null));
        CorrectiveActionResponse d = service.complete(approver, a.actionId(), new CorrectiveActionStepRequest(s.lockVersion(), "조치함"));
        expectCode(() -> service.verify(approver, a.actionId(), new CorrectiveActionStepRequest(d.lockVersion(), null)),
                ErrorCode.FORBIDDEN);
        CorrectiveActionResponse back = service.reopen(approver, a.actionId(), new CorrectiveActionStepRequest(d.lockVersion(), "사진 없음"));
        assertThat(back.status()).isEqualTo(ActionStatus.IN_PROGRESS);
        assertThat(back.completionNote()).isNull();
    }

    @Test
    @DisplayName("다른 현장 담당자·없는 출처·다른 현장 사람·버전 충돌은 막는다. 등록한 사람은 취소할 수 있다")
    void guards() {
        expectCode(() -> service.create(writer, siteId, req(outsider, null)), ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.create(writer, siteId, new SaveCorrectiveActionRequest(ActionSource.NEAR_MISS, 999_999_999L,
                "조치", null, writer, LocalDate.of(2026, 11, 20), null)), ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.create(writer, siteId, new SaveCorrectiveActionRequest(ActionSource.RULE_VIOLATION, 1L,
                "조치", null, writer, LocalDate.of(2026, 11, 20), null)), ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.create(outsider, siteId, req(writer, null)), ErrorCode.SITE_NOT_ALLOWED);

        CorrectiveActionResponse a = service.create(writer, siteId, req(writer, null));
        expectCode(() -> service.start(writer, a.actionId(), new CorrectiveActionStepRequest(a.lockVersion() + 1, null)),
                ErrorCode.CONFLICT_MODIFIED);
        CorrectiveActionResponse edited = service.update(writer, a.actionId(), req(approver, a.lockVersion()));
        assertThat(edited.assigneeId()).isEqualTo(approver);
        CorrectiveActionResponse canceled = service.cancel(writer, a.actionId(), new CorrectiveActionStepRequest(edited.lockVersion(), "중복 등록"));
        assertThat(canceled.status()).isEqualTo(ActionStatus.CANCELED);
        assertThat(service.list(writer, siteId, List.of(ActionStatus.OPEN, ActionStatus.IN_PROGRESS), null, null)).isEmpty();
        assertThat(service.list(writer, siteId, null, ActionSource.OTHER, null)).hasSize(1);
    }
}
