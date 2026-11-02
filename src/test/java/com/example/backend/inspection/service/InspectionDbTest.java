package com.example.backend.inspection.service;

import com.example.backend.approval.entity.ApprovalStatus;
import com.example.backend.approval.service.ApprovalService;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.inspection.dto.CreateInspectionRequest;
import com.example.backend.inspection.dto.InspectionResponse;
import com.example.backend.inspection.dto.SaveInspectionRequest;
import com.example.backend.inspection.dto.SaveInspectionRequest.Answer;
import com.example.backend.inspection.entity.InspectionResult;
import com.example.backend.inspection.entity.InspectionStatus;
import com.example.backend.inspection.entity.InspectionTarget;
import com.example.backend.inspection.entity.ItemResult;
import com.example.backend.preview.service.PreviewService;
import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.site.dto.EquipmentRequest;
import com.example.backend.site.dto.EquipmentResponse;
import com.example.backend.site.dto.ZoneRequest;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMember;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.entity.SiteRole;
import com.example.backend.site.repository.SiteMemberRepository;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.site.service.EquipmentService;
import com.example.backend.site.service.ZoneService;
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

/** 장비·구역 점검표와 설비 (실제 DB, 끝나면 롤백) */
@SpringBootTest
@Transactional
@DisplayName("점검표·설비 API (실제 DB)")
class InspectionDbTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);

    @Autowired InspectionService service;
    @Autowired InspectionTemplates templates;
    @Autowired EquipmentService equipmentService;
    @Autowired ZoneService zoneService;
    @Autowired ApprovalService approvalService;
    @Autowired PreviewService previewService;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired SiteMemberRepository siteMemberRepository;

    private String tag;
    private Long adminId;
    private Long siteId;
    private Long writer;
    private Long zoneId;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        adminId = userRepository.save(User.bootstrapAdmin("in-admin-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("점검현장-" + tag, SiteMode.GUIDE)).getId();   // 안내 모드: 자기 승인 가능
        User u = new User("in-" + UUID.randomUUID() + "@example.com", "HASH", "작성자");
        u.verifyEmail(EmailVerifiedVia.ADMIN, LocalDateTime.now());
        u.approve(UserRole.MEMBER);
        writer = userRepository.save(u).getId();
        siteMemberRepository.save(new SiteMember(writer, siteId, SiteRole.WRITER, adminId));
        zoneId = zoneService.create(adminId, siteId, new ZoneRequest("R-201", "반응기 하부", null, false, null, null)).zoneId();
    }

    private InspectionResponse zoneInspection() {
        return service.create(writer, siteId, new CreateInspectionRequest(InspectionTarget.ZONE, zoneId, null, null, DAY,
                "ZONE_HOT_WORK"));
    }

    /** 모든 항목을 같은 결과로 (NG 면 내용 포함) */
    private InspectionResponse answerAll(InspectionResponse i, ItemResult result) {
        List<Answer> answers = i.items().stream()
                .map(x -> new Answer(x.itemCode(), result, result == ItemResult.NG ? "불량" : null)).toList();
        return service.save(writer, i.inspectionId(), new SaveInspectionRequest(i.lockVersion(), null, answers));
    }

    @Test
    @DisplayName("양식: 기동 때 읽은 양식 3종, 구역 양식은 ZONE")
    void templates() {
        assertThat(service.templates()).extracting(InspectionTemplates.Template::code)
                .contains("ZONE_DAILY", "ZONE_HOT_WORK", "EQUIPMENT_DAILY");
        assertThat(templates.find("ZONE_DAILY")).get().satisfies(t -> {
            assertThat(t.targetType()).isEqualTo(InspectionTarget.ZONE);
            assertThat(t.items()).hasSize(10);
        });
    }

    @Test
    @DisplayName("만들기: 양식 항목 복사(구역 제목·설명 포함), 같은 날짜·대상·양식 409, 대상 종류가 양식과 다르면 400")
    void create() {
        InspectionResponse i = zoneInspection();

        assertThat(i.status()).isEqualTo(InspectionStatus.DRAFT);
        assertThat(i.zoneName()).isEqualTo("반응기 하부");
        assertThat(i.templateName()).isEqualTo("화기작업 구역 점검");
        assertThat(i.total()).isEqualTo(7);
        assertThat(i.answered()).isZero();
        assertThat(i.canSubmit()).isFalse();
        assertThat(i.items().get(0)).satisfies(x -> {
            assertThat(x.seq()).isEqualTo(1);
            assertThat(x.section()).isEqualTo("화재 예방");
            assertThat(x.detail()).isNotBlank();
        });
        expectCode(this::zoneInspection, ErrorCode.INVALID_STATE);
        expectCode(() -> service.create(writer, siteId, new CreateInspectionRequest(InspectionTarget.ZONE, zoneId, null, null,
                DAY, "EQUIPMENT_DAILY")), ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.create(writer, siteId, new CreateInspectionRequest(InspectionTarget.ZONE, zoneId, null, null,
                DAY, "NO_SUCH")), ErrorCode.INVALID_REQUEST);
        assertThat(service.list(writer, siteId, DAY, DAY)).extracting(InspectionResponse::inspectionId)
                .containsExactly(i.inspectionId());
    }

    @Test
    @DisplayName("결과 저장: 보낸 항목만 바뀜, 불량인데 내용 없으면 400, 없는 항목 400, 저장마다 lockVersion 증가·예전 버전 409")
    void save() {
        InspectionResponse i = zoneInspection();
        String first = i.items().get(0).itemCode();
        String second = i.items().get(1).itemCode();

        InspectionResponse saved = service.save(writer, i.inspectionId(), new SaveInspectionRequest(i.lockVersion(), null,
                List.of(new Answer(first, ItemResult.OK, null), new Answer(second, ItemResult.NG, "방화포 찢어짐"))));

        assertThat(saved.answered()).isEqualTo(2);
        assertThat(saved.items().get(1).note()).isEqualTo("방화포 찢어짐");
        assertThat(saved.lockVersion()).isEqualTo(i.lockVersion() + 1);
        expectCode(() -> service.save(writer, i.inspectionId(), new SaveInspectionRequest(i.lockVersion(), null,
                List.of(new Answer(first, ItemResult.NA, null)))), ErrorCode.CONFLICT_MODIFIED);
        expectCode(() -> service.save(writer, i.inspectionId(), new SaveInspectionRequest(saved.lockVersion(), null,
                List.of(new Answer(first, ItemResult.NG, " ")))), ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.save(writer, i.inspectionId(), new SaveInspectionRequest(saved.lockVersion(), null,
                List.of(new Answer("NO_SUCH", ItemResult.OK, null)))), ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("제출: 다 적지 않으면 409, 다 적으면 결과 확정·승인 요청(PENDING), 그 사이 저장 409, 승인되면 APPROVED")
    void submitAndApprove() {
        InspectionResponse i = zoneInspection();
        expectCode(() -> service.submit(writer, i.inspectionId()), ErrorCode.INVALID_STATE);

        InspectionResponse answered = answerAll(i, ItemResult.OK);
        assertThat(answered.expectedResult()).isEqualTo(InspectionResult.PASS);
        assertThat(answered.canSubmit()).isTrue();
        InspectionResponse submitted = service.submit(writer, i.inspectionId());

        assertThat(submitted.status()).isEqualTo(InspectionStatus.PENDING);
        assertThat(submitted.result()).isEqualTo(InspectionResult.PASS);
        assertThat(submitted.approvalStatus()).isEqualTo(ApprovalStatus.PENDING);
        expectCode(() -> service.save(writer, i.inspectionId(), new SaveInspectionRequest(submitted.lockVersion(), null,
                List.of())), ErrorCode.INVALID_STATE);
        assertThat(previewService.ofTarget(writer, ApprovalTarget.INSPECTION, i.inspectionId()).targetType())
                .isEqualTo("INSPECTION");

        approvalService.approve(adminId, submitted.approvalId(), null);

        InspectionResponse approved = service.get(writer, i.inspectionId());
        assertThat(approved.status()).isEqualTo(InspectionStatus.APPROVED);
        assertThat(approved.approvalStatus()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    @DisplayName("반려되면 같은 점검표가 작성 중으로 돌아가 결과를 고쳐 다시 제출, 회수해도 작성 중")
    void rejectAndCancel() {
        InspectionResponse i = answerAll(zoneInspection(), ItemResult.NG);
        InspectionResponse submitted = service.submit(writer, i.inspectionId());
        assertThat(submitted.result()).isEqualTo(InspectionResult.FAIL);

        approvalService.reject(adminId, submitted.approvalId(), "조치 후 재점검", null);

        InspectionResponse back = service.get(writer, i.inspectionId());
        assertThat(back.status()).isEqualTo(InspectionStatus.DRAFT);
        assertThat(back.approvalStatus()).isEqualTo(ApprovalStatus.REJECTED);
        InspectionResponse fixed = answerAll(back, ItemResult.OK);
        InspectionResponse again = service.submit(writer, i.inspectionId());
        assertThat(again.result()).isEqualTo(InspectionResult.PASS);

        approvalService.cancel(writer, again.approvalId(), null);
        assertThat(service.get(writer, fixed.inspectionId()).status()).isEqualTo(InspectionStatus.DRAFT);
    }

    @Test
    @DisplayName("설비: 현장 관리자만 등록, 코드 중복 409, 종류 형식 400. 장비 점검표는 구역을 비우면 설비 구역")
    void equipment() {
        EquipmentRequest grinder = new EquipmentRequest("GR-01", "그라인더 1호", "GRINDER", zoneId, DAY.minusDays(1), null);
        expectCode(() -> equipmentService.create(writer, siteId, grinder), ErrorCode.FORBIDDEN);
        EquipmentResponse e = equipmentService.create(adminId, siteId, grinder);
        assertThat(e.inspectionOverdue()).isTrue();
        expectCode(() -> equipmentService.create(adminId, siteId, grinder), ErrorCode.INVALID_STATE);
        expectCode(() -> equipmentService.create(adminId, siteId,
                new EquipmentRequest("GR-02", "그라인더 2호", "grinder", null, null, null)), ErrorCode.INVALID_REQUEST);
        assertThat(equipmentService.list(writer, siteId, false)).extracting(EquipmentResponse::equipmentId)
                .containsExactly(e.equipmentId());

        InspectionResponse i = service.create(writer, siteId, new CreateInspectionRequest(InspectionTarget.EQUIPMENT, null,
                e.equipmentId(), null, DAY, "EQUIPMENT_DAILY"));

        assertThat(i.equipmentName()).isEqualTo("그라인더 1호");
        assertThat(i.zoneId()).isEqualTo(zoneId);
        equipmentService.update(adminId, siteId, e.equipmentId(),
                new EquipmentRequest("GR-01", "그라인더 1호", "GRINDER", zoneId, null, false));
        expectCode(() -> service.create(writer, siteId, new CreateInspectionRequest(InspectionTarget.EQUIPMENT, null,
                e.equipmentId(), null, DAY.plusDays(1), "EQUIPMENT_DAILY")), ErrorCode.INVALID_REQUEST);
    }
}
