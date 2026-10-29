package com.example.backend.inspection;

import com.example.backend.attachment.entity.*;
import com.example.backend.attachment.repository.AttachmentRepository;
import com.example.backend.attachment.repository.PhotoCheckRepository;
import com.example.backend.inspection.entity.*;
import com.example.backend.inspection.repository.InspectionItemRepository;
import com.example.backend.inspection.repository.InspectionRepository;
import com.example.backend.permit.dto.CreatePermitRequest;
import com.example.backend.permit.entity.*;
import com.example.backend.permit.repository.PermitCheckRepository;
import com.example.backend.permit.repository.PermitEventRepository;
import com.example.backend.permit.repository.PermitRepository;
import com.example.backend.permit.service.PermitService;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.site.entity.Equipment;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.entity.WorkZone;
import com.example.backend.site.repository.EquipmentRepository;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.site.repository.WorkZoneRepository;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.work.dto.CreateWorkRequest;
import com.example.backend.work.service.WorkService;
import jakarta.persistence.EntityManager;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 허가 체크·이벤트, 점검표·항목, 첨부·사진 확인 엔티티를 실제 로컬 DB로 확인 (끝나면 롤백).
 * V4 의 CHECK·외래키·유니크, V9 의 잠금 트리거(제출 후 수정 불가, 승인된 허가에만 이벤트, 이벤트 변경·삭제 불가)를 본다.
 */
@SpringBootTest
@Transactional
@DisplayName("허가 체크·이벤트·점검표·첨부 엔티티 DB 통합")
class PermitInspectionEntitiesDbTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 29, 13, 0);
    private static final LocalDateTime TO = FROM.plusHours(2);
    private static final LocalDate D = FROM.toLocalDate();
    private static final String SHA = "0123456789abcdef".repeat(4);

    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired WorkZoneRepository zoneRepository;
    @Autowired EquipmentRepository equipmentRepository;
    @Autowired WorkService workService;
    @Autowired PermitService permitService;
    @Autowired PermitRepository permitRepository;
    @Autowired PermitCheckRepository checkRepository;
    @Autowired PermitEventRepository eventRepository;
    @Autowired InspectionRepository inspectionRepository;
    @Autowired InspectionItemRepository itemRepository;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired PhotoCheckRepository photoCheckRepository;
    @Autowired EntityManager em;

    private Long userId;
    private Long siteId;
    private WorkZone zone;
    private Equipment grinder;
    private Long workId;
    private Long permitId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        userId = userRepository.save(User.bootstrapAdmin("b5-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("테스트현장-" + tag, SiteMode.VERIFY)).getId();
        zone = zoneRepository.save(new WorkZone(siteId, "R-201", "반응기 하부", "PROCESS", false, null));
        grinder = equipmentRepository.save(new Equipment(siteId, zone.getId(), "GR-01", "그라인더", "GRINDER", null));
        workId = workService.create(userId, new CreateWorkRequest(siteId, D, "R-201 배관 절단", null, WorkType.HOT,
                FROM, TO, 2)).workId();
        permitId = permitService.create(userId, new CreatePermitRequest(workId, PermitType.HOT, FROM, TO,
                "김감시", null, null)).permitId();
        em.flush();
    }

    private void setPermitStatus(String status) {
        em.flush();
        em.createNativeQuery("UPDATE permits SET status = ?1 WHERE id = ?2")
                .setParameter(1, status).setParameter(2, permitId).executeUpdate();
        em.clear();
    }

    private Permit permit() {
        return permitRepository.findById(permitId).orElseThrow();
    }

    @Test
    @DisplayName("체크 항목·점검표·항목·첨부·사진 확인이 제약을 통과해 저장되고 다시 읽힌다")
    void saveAndReload() {
        // 허가 체크 항목
        PermitCheck ext = checkRepository.save(new PermitCheck(permitId, "extinguisher_ready", "소화기 비치"));
        ext.mark(true, null);
        PermitCheck spark = checkRepository.save(new PermitCheck(permitId, "spark_prevention", "불티 비산 방지"));
        spark.mark(false, "방지포 수량 부족, 추가 요청");

        // 구역 점검표 + 항목 3개 → 종합 결과 PARTIAL 로 제출
        Inspection zoneInsp = Inspection.forZone(siteId, zone.getId(), D, "HOT_WORK_ZONE", userId);
        zoneInsp.linkWork(workId);
        zoneInsp = inspectionRepository.saveAndFlush(zoneInsp);
        InspectionItem i1 = new InspectionItem(zoneInsp.getId(), 1, "FLAMMABLE_CLEARED", "반경 11m 가연물 제거");
        i1.record(ItemResult.OK, null);
        InspectionItem i2 = new InspectionItem(zoneInsp.getId(), 2, "SPARK_SHIELD", "불티 비산방지포 설치");
        i2.record(ItemResult.NG, "방지포 1장 부족");
        InspectionItem i3 = new InspectionItem(zoneInsp.getId(), 3, "DRAIN_COVER", "배수구 덮개");
        i3.record(ItemResult.NA, null);
        itemRepository.saveAll(List.of(i1, i2, i3));
        zoneInsp.submit(InspectionResult.of(List.of(i1.getResult(), i2.getResult(), i3.getResult())));

        // 장비 점검표
        Inspection eq = inspectionRepository.save(Inspection.forEquipment(siteId, grinder.getId(), zone.getId(), D,
                "GRINDER_DAILY", userId));

        // 첨부 2개 (같은 해시) + 사진 확인 (재사용 의심)
        Attachment first = attachmentRepository.save(new Attachment(siteId, AttachmentOwner.INSPECTION, zoneInsp.getId(),
                AttachmentKind.PHOTO, "zone.jpg", "photos/" + UUID.randomUUID() + ".jpg", "image/jpeg", 2048L, SHA,
                FROM.minusMinutes(10), userId));
        Attachment draft = attachmentRepository.save(new Attachment(siteId, AttachmentOwner.DRAFT, 1L, null, "tbm.jpg",
                "photos/" + UUID.randomUUID() + ".jpg", "image/jpeg", 2048L, SHA, null, userId));
        draft.moveTo(AttachmentOwner.TBM, 777L);
        Long dupOf = attachmentRepository.findFirstBySiteIdAndSha256AndIdNotOrderByIdAsc(siteId, SHA, draft.getId())
                .orElseThrow().getId();
        photoCheckRepository.save(new PhotoCheck(draft.getId(), PhotoCheckType.TBM, 4, D, null, dupOf,
                PhotoCheckResult.UNSURE, "{\"reason\":\"같은 사진 재사용 의심\"}", "mock-vision", null));

        em.flush();
        em.clear();

        // 다시 읽기
        assertThat(checkRepository.findByPermitIdOrderByIdAsc(permitId)).extracting(PermitCheck::isChecked)
                .containsExactly(true, false);
        assertThat(checkRepository.findByPermitIdAndItemCode(permitId, "spark_prevention").orElseThrow().getNote())
                .contains("방지포");

        Inspection reloaded = inspectionRepository.findByIdAndSiteId(zoneInsp.getId(), siteId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InspectionStatus.PENDING);
        assertThat(reloaded.getResult()).isEqualTo(InspectionResult.PARTIAL);
        assertThat(inspectionRepository.findByWorkIdOrderByIdAsc(workId)).hasSize(1);
        assertThat(itemRepository.findByInspectionIdOrderBySeqAsc(zoneInsp.getId())).extracting(InspectionItem::getResult)
                .containsExactly(ItemResult.OK, ItemResult.NG, ItemResult.NA);
        assertThat(inspectionRepository.findTopByEquipmentIdOrderByInspectDateDescIdDesc(grinder.getId()).orElseThrow()
                .getId()).isEqualTo(eq.getId());
        assertThat(inspectionRepository.findBySiteIdAndInspectDateOrderByIdAsc(siteId, D)).hasSize(2);

        assertThat(attachmentRepository.findByOwnerTypeAndOwnerIdOrderByIdAsc(AttachmentOwner.TBM, 777L))
                .extracting(Attachment::getId).containsExactly(draft.getId());
        PhotoCheck pc = photoCheckRepository.findTopByAttachmentIdOrderByIdDesc(draft.getId()).orElseThrow();
        assertThat(pc.getDuplicateOf()).isEqualTo(first.getId());
        assertThat(pc.getResult()).isEqualTo(PhotoCheckResult.UNSURE);
        assertThat(pc.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("승인된 허가: 연장 요청 → 승인 → 실제 종료 시각 = 승인된 연장, 인수인계 요청")
    void eventsOnApprovedPermit() {
        setPermitStatus("APPROVED");
        Permit p = permit();

        PermitEvent extend = eventRepository.save(PermitEvent.extend(p, "자재 반입 지연", TO.plusHours(1), userId));
        extend.approve(userId, FROM.plusHours(1));
        eventRepository.save(PermitEvent.handover(p, "주간 → 야간 교대", "박야간", userId));
        em.flush();
        em.clear();

        assertThat(eventRepository.findLatestApprovedExtension(permitId)).contains(TO.plusHours(1));
        assertThat(eventRepository.findByPermitIdOrderByCreatedAtAscIdAsc(permitId))
                .extracting(PermitEvent::getEventType, PermitEvent::getStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(PermitEventType.EXTEND, PermitEventStatus.APPROVED),
                        org.assertj.core.groups.Tuple.tuple(PermitEventType.HANDOVER, PermitEventStatus.REQUESTED));
        assertThat(eventRepository.existsByPermitIdAndEventTypeAndStatus(permitId, PermitEventType.HANDOVER,
                PermitEventStatus.REQUESTED)).isTrue();
    }

    // ---- DB 잠금 (트리거·외래키). 엔티티·서비스 검사를 건너뛴 경우에도 DB가 막는지 ----

    @Test
    @DisplayName("작성 중인 허가에는 이벤트를 넣을 수 없다 (trg_pe_ins)")
    void eventOnDraftPermitRejected() {
        Permit p = permit();
        assertThatThrownBy(() -> {
            eventRepository.save(PermitEvent.suspend(p, "가스 농도 상승", null, userId));
            em.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("이미 승인된 연장보다 이른 연장은 넣을 수 없다 (trg_pe_ins)")
    void extendBeforeApprovedExtensionRejected() {
        setPermitStatus("APPROVED");
        Permit p = permit();
        PermitEvent first = eventRepository.save(PermitEvent.extend(p, "1차 연장", TO.plusHours(2), userId));
        first.approve(userId, FROM.plusHours(1));
        em.flush();

        assertThatThrownBy(() -> {
            eventRepository.save(PermitEvent.extend(p, "2차 연장", TO.plusHours(1), userId));   // 허가 종료보다는 뒤지만 1차 연장보다 이름
            em.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("결정된 허가 이벤트는 바꿀 수 없다 (trg_pe_final)")
    void decidedEventImmutable() {
        setPermitStatus("APPROVED");
        PermitEvent e = eventRepository.save(PermitEvent.closeCheck(permit(), "작업 완료, 화기 잔불 확인", userId));
        e.approve(userId, TO);
        em.flush();

        assertThatThrownBy(() -> em.createNativeQuery("UPDATE permit_events SET status = 'REJECTED' WHERE id = ?1")
                .setParameter(1, e.getId()).executeUpdate()).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("허가 이벤트는 지울 수 없다 (trg_pe_no_delete)")
    void eventNotDeletable() {
        setPermitStatus("APPROVED");
        PermitEvent e = eventRepository.saveAndFlush(PermitEvent.suspend(permit(), "강풍", null, userId));

        assertThatThrownBy(() -> em.createNativeQuery("DELETE FROM permit_events WHERE id = ?1")
                .setParameter(1, e.getId()).executeUpdate()).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("승인 요청된 허가의 체크 항목은 추가할 수 없다 (trg_pc_lock_ins)")
    void permitChecksLockedAfterSubmit() {
        setPermitStatus("PENDING");

        assertThatThrownBy(() -> {
            checkRepository.save(new PermitCheck(permitId, "extinguisher_ready", "소화기 비치"));
            em.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("제출된 점검표에는 항목을 추가할 수 없다 (trg_ii_lock_ins)")
    void inspectionItemsLockedAfterSubmit() {
        Inspection insp = inspectionRepository.saveAndFlush(Inspection.forZone(siteId, zone.getId(), D, "HOT_WORK_ZONE", userId));
        insp.submit(InspectionResult.PASS);
        em.flush();

        assertThatThrownBy(() -> {
            itemRepository.save(new InspectionItem(insp.getId(), 1, "LATE_ITEM", "제출 뒤 추가"));
            em.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("다른 현장의 장비로는 점검표를 만들 수 없다 (fk_insp_equipment)")
    void otherSiteEquipmentRejected() {
        Long otherSite = siteRepository.save(new Site("테스트현장2-" + UUID.randomUUID().toString().substring(0, 8),
                SiteMode.VERIFY)).getId();

        assertThatThrownBy(() -> {
            inspectionRepository.save(Inspection.forEquipment(otherSite, grinder.getId(), null, D, "GRINDER_DAILY", userId));
            em.flush();
        }).isInstanceOf(RuntimeException.class);
    }
}
