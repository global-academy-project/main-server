package com.example.backend.preview.service;

import com.example.backend.approval.entity.ApprovalStatus;
import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.repository.ApprovalRepository;
import com.example.backend.approval.service.ApprovalService;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.permit.dto.AddMeasurementsRequest;
import com.example.backend.permit.dto.CreatePermitRequest;
import com.example.backend.permit.entity.MeasurementItem;
import com.example.backend.permit.entity.PermitType;
import com.example.backend.permit.service.PermitService;
import com.example.backend.preview.dto.PreviewMark;
import com.example.backend.preview.dto.PreviewResponse;
import com.example.backend.risk.entity.RiskAssessment;
import com.example.backend.risk.entity.RiskMethod;
import com.example.backend.risk.repository.RiskAssessmentRepository;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.tbm.entity.TbmRecord;
import com.example.backend.tbm.repository.TbmRecordRepository;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.work.dto.CreateWorkRequest;
import com.example.backend.work.service.WorkService;
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

/** 문서 미리보기: 상태별 표시 문구, 결재 연결, 저장하지 않음 (실제 DB, 끝나면 롤백) */
@SpringBootTest
@Transactional
@DisplayName("문서 미리보기 (실제 DB)")
class PreviewDbTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);

    @Autowired PreviewService previewService;
    @Autowired PermitService permitService;
    @Autowired WorkService workService;
    @Autowired ApprovalService approvalService;
    @Autowired ApprovalRepository approvalRepository;
    @Autowired RiskAssessmentRepository riskRepository;
    @Autowired TbmRecordRepository tbmRepository;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;

    private Long userId;
    private Long siteId;
    private Long workId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        userId = userRepository.save(User.bootstrapAdmin("preview-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("미리보기현장-" + tag, SiteMode.GUIDE)).getId();   // 안내 모드: 자기 승인 가능
        workId = workService.create(userId, new CreateWorkRequest(siteId, DAY, "R-201 배관 플랜지 볼트 교체", null,
                WorkType.HOT, DAY.atTime(13, 0), DAY.atTime(15, 0), 3)).workId();
    }

    private Long hotPermit() {
        return permitService.create(userId, new CreatePermitRequest(workId, PermitType.HOT, DAY.atTime(13, 0),
                DAY.atTime(15, 0), "김감시", "이반장",
                Map.of("extinguisher_ready", true, "spark_prevention", true, "flammable_vapor_removed", true))).permitId();
    }

    private void submit(Long permitId) {
        permitService.addMeasurements(userId, permitId, new AddMeasurementsRequest(List.of(new AddMeasurementsRequest.Item(
                MeasurementItem.LEL, new BigDecimal("3"), DAY.atTime(12, 30), "플랜지 주변", "박측정"))));
        permitService.check(userId, permitId);
        permitService.submit(userId, permitId);
    }

    @Test
    @DisplayName("작성 중 허가서: '작성 중 · 정식 문서 아님', 양식 종류·판, 작업, 결재 없음")
    void draftPermit() {
        Long permitId = hotPermit();

        PreviewResponse p = previewService.ofTarget(userId, ApprovalTarget.PERMIT, permitId);

        assertThat(p.mark()).isEqualTo(PreviewMark.DRAFT);
        assertThat(p.markText()).isEqualTo("작성 중 · 정식 문서 아님");
        assertThat(p.official()).isFalse();
        assertThat(p.formType()).isEqualTo("HOT_WORK_PERMIT");
        assertThat(p.formSchemaVersion()).isEqualTo("v1");
        assertThat(p.title()).endsWith("화기작업허가");
        assertThat(p.work().workId()).isEqualTo(workId);
        assertThat(p.content().path("permitId").asLong()).isEqualTo(permitId);
        assertThat(p.content().path("form").path("spark_prevention").asBoolean()).isTrue();
        assertThat(p.approval()).isNull();
    }

    @Test
    @DisplayName("승인 대기: 결재에서 열면 '승인 전 미리보기', 승인 후에는 표시 없는 정식 문서와 결재 정보")
    void pendingThenApproved() {
        Long permitId = hotPermit();
        submit(permitId);
        Long approvalId = approvalRepository.findByTargetTypeAndTargetIdAndStatus(ApprovalTarget.PERMIT, permitId,
                ApprovalStatus.PENDING).orElseThrow().getId();

        PreviewResponse pending = previewService.ofApproval(userId, approvalId);
        assertThat(pending.mark()).isEqualTo(PreviewMark.PENDING);
        assertThat(pending.markText()).isEqualTo("승인 전 미리보기");
        assertThat(pending.targetId()).isEqualTo(permitId);
        assertThat(pending.approval().summary().approvalId()).isEqualTo(approvalId);

        approvalService.approve(userId, approvalId, null);
        PreviewResponse approved = previewService.ofTarget(userId, ApprovalTarget.PERMIT, permitId);
        assertThat(approved.official()).isTrue();
        assertThat(approved.mark()).isNull();
        assertThat(approved.markText()).isNull();
        assertThat(approved.approval().summary().status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approved.approval().summary().selfApproval()).isTrue();
        // 승인 대기 때 본 내용과 승인 후 내용이 같다 (상태·버전만 다름)
        assertThat(approved.content().path("form")).isEqualTo(pending.content().path("form"));
        assertThat(approved.content().path("measurements")).isEqualTo(pending.content().path("measurements"));
    }

    @Test
    @DisplayName("위험성평가: formData 를 JSON 으로 풀어 주고, 판은 엔티티 값")
    void risk() {
        RiskAssessment r = RiskAssessment.daily(siteId, workId, DAY, userId);
        r.updateContent(null, null, DAY, RiskMethod.FREQ_SEVERITY, "v1", "{\"workSummary\": \"배관 교체\"}");
        Long riskId = riskRepository.saveAndFlush(r).getId();

        PreviewResponse p = previewService.ofTarget(userId, ApprovalTarget.RISK, riskId);

        assertThat(p.formType()).isEqualTo("RISK_ASSESSMENT");
        assertThat(p.formSchemaVersion()).isEqualTo("v1");
        assertThat(p.mark()).isEqualTo(PreviewMark.DRAFT);
        assertThat(p.content().path("formData").path("workSummary").asString()).isEqualTo("배관 교체");
        assertThat(p.title()).contains("R-201");
    }

    @Test
    @DisplayName("TBM: 실시 완료(확인 전)는 '승인 전 미리보기'")
    void tbmDone() {
        TbmRecord t = new TbmRecord(siteId, DAY, null, null, userId);
        t.complete(DAY.atTime(8, 30), 3);
        Long tbmId = tbmRepository.saveAndFlush(t).getId();

        PreviewResponse p = previewService.ofTarget(userId, ApprovalTarget.TBM, tbmId);

        assertThat(p.formType()).isEqualTo("TBM");
        assertThat(p.mark()).isEqualTo(PreviewMark.PENDING);
        assertThat(p.work()).isNull();
    }

    @Test
    @DisplayName("지원하지 않는 문서 종류는 400, 다른 현장 사람은 볼 수 없음")
    void unsupportedAndOtherSite() {
        expectCode(() -> previewService.ofTarget(userId, ApprovalTarget.WORK, workId), ErrorCode.INVALID_REQUEST);

        Long permitId = hotPermit();
        User outsider = new User("preview-out-" + UUID.randomUUID() + "@example.com", "HASH", "외부");
        outsider.verifyEmail(com.example.backend.user.entity.EmailVerifiedVia.ADMIN, LocalDateTime.now());
        outsider.approve(com.example.backend.user.entity.UserRole.MEMBER);
        Long outsiderId = userRepository.save(outsider).getId();
        expectCode(() -> previewService.ofTarget(outsiderId, ApprovalTarget.PERMIT, permitId), ErrorCode.SITE_NOT_ALLOWED);
    }
}
