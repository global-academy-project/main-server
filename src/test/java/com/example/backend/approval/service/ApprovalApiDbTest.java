package com.example.backend.approval.service;

import com.example.backend.approval.dto.ApprovalDetail;
import com.example.backend.approval.dto.ApprovalSummary;
import com.example.backend.approval.entity.Approval;
import com.example.backend.approval.entity.ApprovalPolicy;
import com.example.backend.approval.entity.ApprovalStatus;
import com.example.backend.approval.entity.ApprovalStepStatus;
import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.entity.ApproverRole;
import com.example.backend.approval.repository.ApprovalPolicyRepository;
import com.example.backend.approval.repository.ApprovalRepository;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.notification.entity.Notification;
import com.example.backend.notification.entity.NotificationRecipient;
import com.example.backend.notification.repository.NotificationRecipientRepository;
import com.example.backend.notification.repository.NotificationRepository;
import com.example.backend.permit.dto.AddMeasurementsRequest;
import com.example.backend.permit.dto.CreatePermitRequest;
import com.example.backend.permit.entity.MeasurementItem;
import com.example.backend.permit.entity.PermitStatus;
import com.example.backend.permit.entity.PermitType;
import com.example.backend.permit.repository.PermitRepository;
import com.example.backend.permit.service.PermitService;
import com.example.backend.risk.entity.RiskAssessment;
import com.example.backend.risk.entity.RiskAssessmentItem;
import com.example.backend.risk.entity.RiskLevel;
import com.example.backend.risk.entity.RiskStatus;
import com.example.backend.risk.repository.RiskAssessmentItemRepository;
import com.example.backend.risk.repository.RiskAssessmentRepository;
import com.example.backend.risk.service.RiskAssessmentService;
import com.example.backend.rule.entity.EvaluationResult;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.site.entity.SiteMember;
import com.example.backend.site.entity.SiteRole;
import com.example.backend.site.repository.SiteMemberRepository;
import com.example.backend.user.entity.EmailVerifiedVia;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserRole;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.work.dto.CreateWorkRequest;
import com.example.backend.work.service.WorkService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결재: 허가서·위험성평가 승인 요청 → 승인함 → 승인·반려·회수 (실제 DB, 끝나면 롤백).
 * 현장 인원: 작성자 W, 다른 작성자 W2, 승인자 P, 현장 관리자 S.
 */
@SpringBootTest
@Transactional
@DisplayName("결재 API (실제 DB)")
class ApprovalApiDbTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 11, 2, 13, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 11, 2, 15, 0);

    @Autowired ApprovalService approvalService;
    @Autowired PermitService permitService;
    @Autowired RiskAssessmentService riskService;
    @Autowired WorkService workService;
    @Autowired ApprovalRepository approvalRepository;
    @Autowired ApprovalPolicyRepository policyRepository;
    @Autowired PermitRepository permitRepository;
    @Autowired RiskAssessmentRepository riskRepository;
    @Autowired RiskAssessmentItemRepository riskItemRepository;
    @Autowired RecordHistoryRepository historyRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired NotificationRecipientRepository recipientRepository;
    @Autowired UserRepository userRepository;
    @Autowired SiteMemberRepository siteMemberRepository;
    @Autowired JsonMapper jsonMapper;
    @Autowired EntityManager em;

    private Long adminId;
    private Long siteId;
    private Long writer;
    private Long writer2;
    private Long approver;
    private Long siteAdmin;
    private Long workId;

    // ------------------------------------------------------------------ 준비

    /** 현장(모드 지정)과 인원, 작업 1건 */
    private void site(String mode) {
        adminId = userRepository.save(User.bootstrapAdmin("appr-admin-" + UUID.randomUUID() + "@example.com", "HASH",
                LocalDateTime.now())).getId();
        em.createNativeQuery("INSERT INTO sites (name, mode) VALUES (?1, ?2)")
                .setParameter(1, "결재현장-" + UUID.randomUUID()).setParameter(2, mode).executeUpdate();
        siteId = ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
        writer = member("작성자", SiteRole.WRITER);
        writer2 = member("다른작성자", SiteRole.WRITER);
        approver = member("승인자", SiteRole.APPROVER);
        siteAdmin = member("현장관리자", SiteRole.SITE_ADMIN);
        workId = workService.create(writer, new CreateWorkRequest(siteId, LocalDate.of(2026, 11, 2),
                "R-201 하부 배관 플랜지 볼트 교체", "고착 시 그라인더 절단", WorkType.HOT, FROM, TO, 3)).workId();
    }

    private Long member(String name, SiteRole role) {
        User u = new User("appr-" + UUID.randomUUID() + "@example.com", "HASH", name);
        u.verifyEmail(EmailVerifiedVia.ADMIN, LocalDateTime.now());
        u.approve(UserRole.MEMBER);
        Long id = userRepository.save(u).getId();
        siteMemberRepository.save(new SiteMember(id, siteId, role, adminId));
        return id;
    }

    /** 판정 PASS 인 화기 허가서를 by 가 작성해 승인 요청 */
    private Long submittedPermit(Long by) {
        Long id = permitService.create(by, new CreatePermitRequest(workId, PermitType.HOT, FROM, TO, "김감시", "이반장",
                Map.of("extinguisher_ready", true, "spark_prevention", true, "flammable_vapor_removed", true))).permitId();
        permitService.addMeasurements(by, id, new AddMeasurementsRequest(List.of(new AddMeasurementsRequest.Item(
                MeasurementItem.LEL, new BigDecimal("3"), FROM.minusMinutes(30), "플랜지 주변", "박측정"))));
        assertThat(permitService.check(by, id).evaluation().result()).isEqualTo(EvaluationResult.PASS);
        assertThat(permitService.submit(by, id).status()).isEqualTo(PermitStatus.PENDING);
        return id;
    }

    private Approval pendingApprovalOf(ApprovalTarget type, Long targetId) {
        return approvalRepository.findByTargetTypeAndTargetIdAndStatus(type, targetId, ApprovalStatus.PENDING).orElseThrow();
    }

    private PermitStatus permitStatus(Long permitId) {
        em.flush();
        em.clear();
        return permitRepository.findById(permitId).orElseThrow().getStatus();
    }

    private List<Long> recipients(String dedupeKey) {
        em.flush();
        Notification n = notificationRepository.findByDedupeKey(dedupeKey).orElseThrow();
        return em.createQuery("select r from NotificationRecipient r where r.notificationId = :id", NotificationRecipient.class)
                .setParameter("id", n.getId()).getResultList().stream().map(NotificationRecipient::getUserId).toList();
    }

    private List<RecordHistory> history(HistoryTarget type, Long id, HistoryAction action) {
        em.flush();
        return historyRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAscIdAsc(type, id).stream()
                .filter(h -> h.getAction() == action).toList();
    }

    // ------------------------------------------------------------------ 요청

    @Test
    @DisplayName("허가서 승인 요청 → 결재 1단계(승인자 이상), SUBMIT 이력, 승인 요청 알림은 승인자·현장 관리자에게만")
    void submit_createsApproval() {
        site("VERIFY");
        Long permitId = submittedPermit(writer);

        Approval a = pendingApprovalOf(ApprovalTarget.PERMIT, permitId);
        assertThat(a.getRequestedBy()).isEqualTo(writer);
        assertThat(a.getDueAt()).isEqualTo(a.getRequestedAt().plusMinutes(60));   // sites.approval_due_minutes 기본값
        ApprovalDetail d = approvalService.get(approver, a.getId());
        assertThat(d.steps()).singleElement().satisfies(s -> {
            assertThat(s.label()).isEqualTo("승인");
            assertThat(s.requiredRole()).isEqualTo(ApproverRole.APPROVER);
            assertThat(s.status()).isEqualTo(ApprovalStepStatus.PENDING);
        });
        assertThat(d.summary().title()).endsWith("화기작업허가");
        assertThat(d.canDecide()).isTrue();
        assertThat(d.canCancel()).isFalse();
        assertThat(history(HistoryTarget.PERMIT, permitId, HistoryAction.SUBMIT)).hasSize(1);
        assertThat(recipients("APPROVAL_REQUEST:" + a.getId() + ":1")).containsExactlyInAnyOrder(approver, siteAdmin);
    }

    @Test
    @DisplayName("이미 대기 중인 결재가 있으면 같은 문서를 다시 요청할 수 없다")
    void request_twice_rejected() {
        site("VERIFY");
        Long permitId = submittedPermit(writer);
        expectCode(() -> approvalService.request(ApprovalTarget.PERMIT, permitId, siteId, writer), ErrorCode.INVALID_STATE);
    }

    // ------------------------------------------------------------------ 승인함

    @Test
    @DisplayName("승인함: 결재할 수 있는 사람에게만 보이고, 요청자는 '내가 요청한 문서'에서 본다")
    void inbox() {
        site("VERIFY");
        Long permitId = submittedPermit(writer);

        assertThat(approvalService.pending(approver, siteId)).extracting(ApprovalSummary::targetId).containsExactly(permitId);
        assertThat(approvalService.pending(siteAdmin, siteId)).hasSize(1);
        assertThat(approvalService.pending(writer, siteId)).isEmpty();
        assertThat(approvalService.pending(writer2, siteId)).isEmpty();
        assertThat(approvalService.requested(writer, siteId)).extracting(ApprovalSummary::status)
                .containsExactly(ApprovalStatus.PENDING);
        assertThat(approvalService.requested(approver, siteId)).isEmpty();
    }

    // ------------------------------------------------------------------ 승인

    @Test
    @DisplayName("승인 → 결재 APPROVED, 허가서 APPROVED, 결과 알림은 요청자에게")
    void approve_permitApproved() {
        site("VERIFY");
        Long permitId = submittedPermit(writer);
        Long approvalId = pendingApprovalOf(ApprovalTarget.PERMIT, permitId).getId();

        ApprovalDetail d = approvalService.approve(approver, approvalId, "확인했습니다");

        assertThat(d.summary().status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(d.summary().selfApproval()).isFalse();
        assertThat(d.steps()).singleElement().satisfies(s -> {
            assertThat(s.status()).isEqualTo(ApprovalStepStatus.APPROVED);
            assertThat(s.decidedBy()).isEqualTo(approver);
            assertThat(s.decidedByName()).isEqualTo("승인자");
        });
        assertThat(permitStatus(permitId)).isEqualTo(PermitStatus.APPROVED);
        assertThat(recipients("APPROVAL_RESULT:" + approvalId)).containsExactly(writer);
        assertThat(approvalService.pending(approver, siteId)).isEmpty();
    }

    @Test
    @DisplayName("이미 결정된 결재는 다시 승인할 수 없다")
    void approve_twice_rejected() {
        site("VERIFY");
        Long approvalId = pendingApprovalOf(ApprovalTarget.PERMIT, submittedPermit(writer)).getId();
        approvalService.approve(approver, approvalId, null);
        expectCode(() -> approvalService.approve(siteAdmin, approvalId, null), ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("작성자는 승인할 수 없다 (403)")
    void writer_cannotApprove() {
        site("VERIFY");
        Long approvalId = pendingApprovalOf(ApprovalTarget.PERMIT, submittedPermit(writer)).getId();
        expectCode(() -> approvalService.approve(writer2, approvalId, null), ErrorCode.FORBIDDEN);
        assertThat(approvalService.get(writer2, approvalId).canDecide()).isFalse();
    }

    @Test
    @DisplayName("다른 현장 사람은 결재를 볼 수도 없다")
    void otherSite_notAllowed() {
        site("VERIFY");
        Long approvalId = pendingApprovalOf(ApprovalTarget.PERMIT, submittedPermit(writer)).getId();
        Long firstAdmin = adminId;
        site("VERIFY");   // 두 번째 현장과 그 현장의 승인자
        Long otherApprover = approver;
        expectCode(() -> approvalService.get(otherApprover, approvalId), ErrorCode.SITE_NOT_ALLOWED);
        assertThat(approvalService.get(firstAdmin, approvalId).canDecide()).isTrue();   // 회사 관리자는 모든 현장
    }

    @Test
    @DisplayName("검증 모드: 승인자가 자기가 요청한 문서는 승인할 수 없고, 알림에서도 빠진다")
    void verify_selfApproval_forbidden() {
        site("VERIFY");
        Long permitId = submittedPermit(approver);
        Long approvalId = pendingApprovalOf(ApprovalTarget.PERMIT, permitId).getId();

        expectCode(() -> approvalService.approve(approver, approvalId, null), ErrorCode.FORBIDDEN);
        assertThat(approvalService.pending(approver, siteId)).isEmpty();
        assertThat(recipients("APPROVAL_REQUEST:" + approvalId + ":1")).containsExactly(siteAdmin);
    }

    @Test
    @DisplayName("안내 모드: 자기 승인 허용, 결과에 자기 승인 표시")
    void guide_selfApproval_allowed() {
        site("GUIDE");
        Long permitId = submittedPermit(approver);
        Long approvalId = pendingApprovalOf(ApprovalTarget.PERMIT, permitId).getId();

        ApprovalDetail d = approvalService.approve(approver, approvalId, null);

        assertThat(d.summary().selfApproval()).isTrue();
        assertThat(permitStatus(permitId)).isEqualTo(PermitStatus.APPROVED);
    }

    @Test
    @DisplayName("결재선 2단계(검토 승인자 → 승인 현장 관리자): 1단계 뒤 대기 유지, 2단계는 현장 관리자만")
    void twoStepPolicy() {
        site("VERIFY");
        policyRepository.save(new ApprovalPolicy(siteId, ApprovalTarget.PERMIT, 1, "검토", ApproverRole.APPROVER, adminId));
        policyRepository.save(new ApprovalPolicy(siteId, ApprovalTarget.PERMIT, 2, "승인", ApproverRole.SITE_ADMIN, adminId));
        Long permitId = submittedPermit(writer);
        Long approvalId = pendingApprovalOf(ApprovalTarget.PERMIT, permitId).getId();

        ApprovalDetail first = approvalService.approve(approver, approvalId, null);
        assertThat(first.summary().status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(first.summary().currentStep()).isEqualTo(2);
        assertThat(first.summary().currentStepLabel()).isEqualTo("승인");
        assertThat(first.steps()).extracting(ApprovalDetail.Step::status)
                .containsExactly(ApprovalStepStatus.APPROVED, ApprovalStepStatus.PENDING);
        assertThat(permitStatus(permitId)).isEqualTo(PermitStatus.PENDING);
        assertThat(recipients("APPROVAL_REQUEST:" + approvalId + ":2")).containsExactly(siteAdmin);
        expectCode(() -> approvalService.approve(approver, approvalId, null), ErrorCode.FORBIDDEN);

        approvalService.approve(siteAdmin, approvalId, null);
        assertThat(permitStatus(permitId)).isEqualTo(PermitStatus.APPROVED);
    }

    // ------------------------------------------------------------------ 위험성평가 먼저

    @Test
    @DisplayName("작업에 위험성평가가 있으면 승인된 뒤에만 허가서 최종 승인 (위험성평가 요청·승인 → 허가 승인)")
    void permit_waitsForRiskApproval() {
        site("VERIFY");
        RiskAssessment risk = riskRepository.saveAndFlush(RiskAssessment.daily(siteId, workId, LocalDate.of(2026, 11, 2), writer));
        riskItemRepository.saveAndFlush(new RiskAssessmentItem(risk.getId(), 1, "절단", "불티 착화", "FIRE", 3, 4,
                RiskLevel.HIGH, "가연성가스 측정", null, null));
        Long permitId = submittedPermit(writer);
        Long permitApproval = pendingApprovalOf(ApprovalTarget.PERMIT, permitId).getId();

        expectCode(() -> approvalService.approve(approver, permitApproval, null), ErrorCode.INVALID_STATE);
        assertThat(permitStatus(permitId)).isEqualTo(PermitStatus.PENDING);

        assertThat(riskService.submit(writer, risk.getId()).status()).isEqualTo(RiskStatus.PENDING);
        approvalService.approve(approver, pendingApprovalOf(ApprovalTarget.RISK, risk.getId()).getId(), null);
        assertThat(riskService.get(writer, risk.getId()).status()).isEqualTo(RiskStatus.APPROVED);

        approvalService.approve(approver, permitApproval, null);
        assertThat(permitStatus(permitId)).isEqualTo(PermitStatus.APPROVED);
    }

    @Test
    @DisplayName("위험요인이 없는 위험성평가는 승인 요청할 수 없다")
    void risk_withoutItems_cannotSubmit() {
        site("VERIFY");
        RiskAssessment risk = riskRepository.saveAndFlush(RiskAssessment.daily(siteId, workId, LocalDate.of(2026, 11, 2), writer));
        expectCode(() -> riskService.submit(writer, risk.getId()), ErrorCode.INVALID_STATE);
        assertThat(riskService.getByWork(writer, workId).canSubmit()).isFalse();
    }

    // ------------------------------------------------------------------ 반려

    @Test
    @DisplayName("반려(보완 요청): 사유 필수, 누락 항목은 정리해서 이력에, 허가서 REJECTED, 결과 알림")
    void reject_withMissingItems() {
        site("VERIFY");
        Long permitId = submittedPermit(writer);
        Long approvalId = pendingApprovalOf(ApprovalTarget.PERMIT, permitId).getId();

        expectCode(() -> approvalService.reject(approver, approvalId, " ", List.of()), ErrorCode.INVALID_REQUEST);

        ApprovalDetail d = approvalService.reject(approver, approvalId, "소화기 위치 사진이 없습니다",
                Arrays.asList(" 소화기 위치 ", "소화기 위치", "", null, "감시자 서명"));

        assertThat(d.summary().status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(d.summary().comment()).isEqualTo("소화기 위치 사진이 없습니다");
        assertThat(d.missingItems()).containsExactly("소화기 위치", "감시자 서명");
        assertThat(permitStatus(permitId)).isEqualTo(PermitStatus.REJECTED);
        JsonNode after = jsonMapper.readTree(history(HistoryTarget.PERMIT, permitId, HistoryAction.REJECT).get(0).getAfterJson());
        assertThat(after.path("reason").asString()).isEqualTo("소화기 위치 사진이 없습니다");
        assertThat(after.path("missingItems")).hasSize(2);
        assertThat(recipients("APPROVAL_RESULT:" + approvalId)).containsExactly(writer);
    }

    @Test
    @DisplayName("2단계 중 1단계에서 반려하면 남은 단계는 건너뜀")
    void reject_skipsRemaining() {
        site("VERIFY");
        policyRepository.save(new ApprovalPolicy(siteId, ApprovalTarget.PERMIT, 1, "검토", ApproverRole.APPROVER, adminId));
        policyRepository.save(new ApprovalPolicy(siteId, ApprovalTarget.PERMIT, 2, "승인", ApproverRole.SITE_ADMIN, adminId));
        Long approvalId = pendingApprovalOf(ApprovalTarget.PERMIT, submittedPermit(writer)).getId();

        ApprovalDetail d = approvalService.reject(approver, approvalId, "측정 위치 다시", null);

        assertThat(d.steps()).extracting(ApprovalDetail.Step::status)
                .containsExactly(ApprovalStepStatus.REJECTED, ApprovalStepStatus.SKIPPED);
        assertThat(d.missingItems()).isEmpty();
    }

    // ------------------------------------------------------------------ 회수

    @Test
    @DisplayName("회수: 요청자만, 허가서는 작성 중으로 돌아가고 다시 요청하면 새 결재 1단계부터")
    void cancel_thenResubmit() {
        site("VERIFY");
        Long permitId = submittedPermit(writer);
        Long first = pendingApprovalOf(ApprovalTarget.PERMIT, permitId).getId();

        expectCode(() -> approvalService.cancel(approver, first, null), ErrorCode.FORBIDDEN);
        ApprovalDetail d = approvalService.cancel(writer, first, "측정값 다시 넣을게요");

        assertThat(d.summary().status()).isEqualTo(ApprovalStatus.CANCELED);
        assertThat(d.steps()).extracting(ApprovalDetail.Step::status).containsExactly(ApprovalStepStatus.SKIPPED);
        assertThat(permitStatus(permitId)).isEqualTo(PermitStatus.DRAFT);
        assertThat(history(HistoryTarget.PERMIT, permitId, HistoryAction.CANCEL)).hasSize(1);

        assertThat(permitService.submit(writer, permitId).status()).isEqualTo(PermitStatus.PENDING);
        Approval second = pendingApprovalOf(ApprovalTarget.PERMIT, permitId);
        assertThat(second.getId()).isNotEqualTo(first);
        assertThat(second.getCurrentStep()).isEqualTo(1);
        assertThat(approvalRepository.findBySiteIdAndRequestedByOrderByRequestedAtDescIdDesc(siteId, writer,
                PageRequest.of(0, 10)).getContent()).hasSize(2);
    }
}
