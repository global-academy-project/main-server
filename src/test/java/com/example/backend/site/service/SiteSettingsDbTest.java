package com.example.backend.site.service;

import com.example.backend.approval.dto.ApprovalLine;
import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.entity.ApproverRole;
import com.example.backend.approval.service.ApprovalPolicyService;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.site.dto.AdminSiteRequest;
import com.example.backend.site.dto.SiteSettingsRequest;
import com.example.backend.site.dto.SiteSettingsResponse;
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

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

/** 현장 설정·현장 등록(회사 관리자)·결재선 정책 (실제 DB, 끝나면 롤백) */
@SpringBootTest
@Transactional
@DisplayName("현장 설정·결재선 API (실제 DB)")
class SiteSettingsDbTest {

    @Autowired SiteSettingsService settingsService;
    @Autowired ApprovalPolicyService policyService;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired SiteMemberRepository siteMemberRepository;
    @Autowired RecordHistoryRepository historyRepository;

    private String tag;
    private Long adminId;
    private Long siteId;
    private Long siteAdmin;
    private Long writer;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        adminId = userRepository.save(User.bootstrapAdmin("ss-admin-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("설정현장-" + tag, SiteMode.VERIFY)).getId();
        siteAdmin = member("현장관리자", SiteRole.SITE_ADMIN);
        writer = member("작성자", SiteRole.WRITER);
    }

    private Long member(String name, SiteRole role) {
        User u = new User("ss-" + UUID.randomUUID() + "@example.com", "HASH", name);
        u.verifyEmail(EmailVerifiedVia.ADMIN, LocalDateTime.now());
        u.approve(UserRole.MEMBER);
        Long id = userRepository.save(u).getId();
        siteMemberRepository.save(new SiteMember(id, siteId, role, adminId));
        return id;
    }

    private static SiteSettingsRequest settings(String tbm, String log, int dueMinutes) {
        return new SiteSettingsRequest(LocalTime.parse(tbm), LocalTime.parse(log), dueMinutes, 30, " 화학 ", 120, null);
    }

    private static ApprovalLine.StepRequest step(String label, ApproverRole role) {
        return new ApprovalLine.StepRequest(label, role);
    }

    // ------------------------------------------------------------------ 현장 설정

    @Test
    @DisplayName("운영 설정: 작성자는 조회만, 현장 관리자가 변경(이력 남음). TBM 마감이 안전일지 마감보다 늦으면 400")
    void settings() {
        SiteSettingsResponse before = settingsService.get(writer, siteId);
        assertThat(before.tbmDeadline()).isEqualTo(LocalTime.of(9, 0));
        expectCode(() -> settingsService.update(writer, siteId, settings("08:30", "17:00", 30)), ErrorCode.FORBIDDEN);

        SiteSettingsResponse after = settingsService.update(siteAdmin, siteId, settings("08:30", "17:00", 30));

        assertThat(after.tbmDeadline()).isEqualTo(LocalTime.of(8, 30));
        assertThat(after.dailyLogDeadline()).isEqualTo(LocalTime.of(17, 0));
        assertThat(after.approvalDueMinutes()).isEqualTo(30);
        assertThat(after.industry()).isEqualTo("화학");
        assertThat(after.employeeCount()).isEqualTo(120);
        assertThat(historyRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAscIdAsc(HistoryTarget.SITE, siteId)).hasSize(1);
        expectCode(() -> settingsService.update(siteAdmin, siteId, settings("18:00", "17:00", 30)), ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("현장 등록·수정(회사 관리자): 같은 이름 409, 모드·사용 중지 변경, 전체 목록에 사용 중지 현장도 나옴")
    void adminSites() {
        SiteSettingsResponse created = settingsService.create(adminId, new AdminSiteRequest(" 새현장-" + tag + " ", null, null));
        assertThat(created.name()).isEqualTo("새현장-" + tag);
        assertThat(created.mode()).isEqualTo(SiteMode.VERIFY);
        expectCode(() -> settingsService.create(adminId, new AdminSiteRequest("새현장-" + tag, SiteMode.GUIDE, null)),
                ErrorCode.INVALID_STATE);

        SiteSettingsResponse changed = settingsService.updateByAdmin(adminId, created.siteId(),
                new AdminSiteRequest("새현장-" + tag, SiteMode.GUIDE, false));

        assertThat(changed.mode()).isEqualTo(SiteMode.GUIDE);
        assertThat(changed.active()).isFalse();
        assertThat(settingsService.listAll()).extracting(SiteSettingsResponse::siteId).contains(created.siteId(), siteId);
        expectCode(() -> settingsService.updateByAdmin(adminId, siteId, new AdminSiteRequest("새현장-" + tag, null, null)),
                ErrorCode.INVALID_STATE);
    }

    // ------------------------------------------------------------------ 결재선

    @Test
    @DisplayName("결재선: 정책이 없으면 기본 1단계, 현장 관리자가 통째로 바꾸고 빈 목록이면 기본으로. 작성자는 403")
    void approvalLines() {
        List<ApprovalLine> defaults = policyService.list(writer, siteId);
        assertThat(defaults).extracting(ApprovalLine::targetType)
                .contains(ApprovalTarget.PERMIT, ApprovalTarget.RISK, ApprovalTarget.TBM);
        assertThat(defaults).allSatisfy(l -> {
            assertThat(l.defaultLine()).isTrue();
            assertThat(l.steps()).singleElement().satisfies(s -> assertThat(s.requiredRole()).isEqualTo(ApproverRole.APPROVER));
        });
        expectCode(() -> policyService.replace(writer, siteId, ApprovalTarget.PERMIT,
                List.of(step("승인", ApproverRole.APPROVER))), ErrorCode.FORBIDDEN);

        ApprovalLine line = policyService.replace(siteAdmin, siteId, ApprovalTarget.PERMIT,
                List.of(step(" 현장 승인 ", ApproverRole.APPROVER), step("안전관리자 확인", ApproverRole.SITE_ADMIN)));

        assertThat(line.defaultLine()).isFalse();
        assertThat(line.steps()).extracting(ApprovalLine.Step::stepNo, ApprovalLine.Step::label)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "현장 승인"),
                        org.assertj.core.groups.Tuple.tuple(2, "안전관리자 확인"));
        assertThat(policyService.list(writer, siteId)).filteredOn(l -> l.targetType() == ApprovalTarget.PERMIT)
                .singleElement().satisfies(l -> assertThat(l.steps()).hasSize(2));

        ApprovalLine back = policyService.replace(siteAdmin, siteId, ApprovalTarget.PERMIT, List.of());
        assertThat(back.defaultLine()).isTrue();
    }

    @Test
    @DisplayName("결재선 검사: 결재를 안 하는 문서 종류 400, 역할이 앞 단계보다 낮으면 400, 11단계 400")
    void approvalLineValidation() {
        expectCode(() -> policyService.replace(siteAdmin, siteId, ApprovalTarget.WORK,
                List.of(step("승인", ApproverRole.APPROVER))), ErrorCode.INVALID_REQUEST);
        expectCode(() -> policyService.replace(siteAdmin, siteId, ApprovalTarget.RISK,
                List.of(step("관리자", ApproverRole.SITE_ADMIN), step("승인자", ApproverRole.APPROVER))), ErrorCode.INVALID_REQUEST);
        List<ApprovalLine.StepRequest> eleven = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(i -> step(i + "단계", ApproverRole.APPROVER)).toList();
        expectCode(() -> policyService.replace(siteAdmin, siteId, ApprovalTarget.RISK, eleven), ErrorCode.INVALID_REQUEST);
    }
}
