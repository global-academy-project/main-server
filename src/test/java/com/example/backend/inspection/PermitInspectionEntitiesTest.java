package com.example.backend.inspection;

import com.example.backend.attachment.entity.*;
import com.example.backend.inspection.entity.*;
import com.example.backend.permit.entity.Permit;
import com.example.backend.permit.entity.PermitCheck;
import com.example.backend.permit.entity.PermitEvent;
import com.example.backend.permit.entity.PermitEventStatus;
import com.example.backend.permit.entity.PermitEventType;
import com.example.backend.permit.entity.PermitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 허가 체크·이벤트, 점검표, 첨부·사진 확인 엔티티가 DB 규칙을 저장 전에 먼저 막는지 (DB 없이) */
@DisplayName("허가 체크·이벤트·점검표·첨부 엔티티 검증")
class PermitInspectionEntitiesTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 29, 13, 0);
    private static final LocalDateTime TO = FROM.plusHours(2);
    private static final LocalDate D = FROM.toLocalDate();
    private static final String SHA = "a".repeat(64);

    private static Permit permit() {
        Permit p = new Permit(1L, 2L, PermitType.HOT, "HOT-20261029-001", FROM, TO, "김감시", null, null, 3L);
        ReflectionTestUtils.setField(p, "id", 10L);
        return p;
    }

    @Test
    @DisplayName("허가 체크: 코드·이름 필수, 메모 300자")
    void permitCheck() {
        PermitCheck c = new PermitCheck(10L, "extinguisher_ready", "소화기 비치");
        c.mark(true, " ");
        assertThat(c.isChecked()).isTrue();
        assertThat(c.getNote()).isNull();
        assertThatThrownBy(() -> new PermitCheck(10L, " ", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.mark(false, "x".repeat(301))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("허가 이벤트: 연장은 종료 시각 뒤로만, 인수인계는 인수자 필수, 결정은 한 번만")
    void permitEvent() {
        Permit p = permit();
        assertThatThrownBy(() -> PermitEvent.extend(p, "지연", TO, 3L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PermitEvent.handover(p, "교대", " ", 3L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PermitEvent.suspend(p, " ", null, 3L)).isInstanceOf(IllegalArgumentException.class);

        PermitEvent e = PermitEvent.extend(p, "자재 반입 지연", TO.plusHours(1), 3L);
        assertThat(e.getEventType()).isEqualTo(PermitEventType.EXTEND);
        assertThat(e.getSiteId()).isEqualTo(1L);
        e.approve(4L, FROM);
        assertThat(e.getStatus()).isEqualTo(PermitEventStatus.APPROVED);
        assertThatThrownBy(() -> e.reject(4L, FROM)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("점검표: 장비 점검은 장비, 구역 점검은 구역 필수. 제출은 결과 필수, 제출 후 수정 불가")
    void inspection() {
        assertThatThrownBy(() -> Inspection.forEquipment(1L, null, null, D, "GRINDER", 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Inspection.forZone(1L, null, D, "HOT_ZONE", 3L))
                .isInstanceOf(IllegalArgumentException.class);

        Inspection i = Inspection.forZone(1L, 5L, D, "HOT_ZONE", 3L);
        assertThatThrownBy(() -> i.submit(null)).isInstanceOf(IllegalArgumentException.class);
        i.submit(InspectionResult.PASS);
        assertThat(i.getStatus()).isEqualTo(InspectionStatus.PENDING);
        assertThatThrownBy(() -> i.updateContent(D, null)).isInstanceOf(IllegalStateException.class);
        i.withdraw();
        i.updateContent(D.plusDays(1), "{}");
        i.submit(InspectionResult.FAIL);
        i.approve();
        assertThatThrownBy(i::withdraw).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("점검 항목: 불량이면 내용 필수. 종합 결과는 NA 를 빼고 계산")
    void items() {
        InspectionItem item = new InspectionItem(1L, 1, "GRINDER_GUARD", "방호덮개 부착");
        assertThatThrownBy(() -> item.record(ItemResult.NG, " ")).isInstanceOf(IllegalArgumentException.class);
        item.record(ItemResult.NG, "덮개 균열");
        assertThatThrownBy(() -> new InspectionItem(1L, 0, "X", "q")).isInstanceOf(IllegalArgumentException.class);

        assertThat(InspectionResult.of(List.of(ItemResult.OK, ItemResult.NA))).isEqualTo(InspectionResult.PASS);
        assertThat(InspectionResult.of(List.of(ItemResult.OK, ItemResult.NG))).isEqualTo(InspectionResult.PARTIAL);
        assertThat(InspectionResult.of(List.of(ItemResult.NG, ItemResult.NA))).isEqualTo(InspectionResult.FAIL);
        assertThat(InspectionResult.of(Arrays.asList(ItemResult.OK, null))).isNull();   // 결과 안 적은 항목
        assertThat(InspectionResult.of(List.of())).isNull();
    }

    @Test
    @DisplayName("첨부: 해시 형식, 임시저장 첨부만 실제 기록으로 옮김")
    void attachment() {
        assertThatThrownBy(() -> new Attachment(1L, AttachmentOwner.TBM, 1L, null, "a.jpg", "p/a.jpg", null, 10L,
                "ABC", null, 3L)).isInstanceOf(IllegalArgumentException.class);
        Attachment draft = new Attachment(1L, AttachmentOwner.DRAFT, 99L, null, "a.jpg", "p/a.jpg", "image/jpeg",
                10L, SHA, null, 3L);
        assertThat(draft.getKind()).isEqualTo(AttachmentKind.PHOTO);
        assertThatThrownBy(() -> draft.moveTo(AttachmentOwner.DRAFT, 1L)).isInstanceOf(IllegalArgumentException.class);
        draft.moveTo(AttachmentOwner.TBM, 7L);
        assertThat(draft.getOwnerType()).isEqualTo(AttachmentOwner.TBM);
        assertThatThrownBy(() -> draft.moveTo(AttachmentOwner.WORK, 8L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("사진 확인: 인원 0 이상, 자기 자신을 중복 대상으로 둘 수 없음")
    void photoCheck() {
        assertThatThrownBy(() -> new PhotoCheck(1L, PhotoCheckType.TBM, -1, null, null, null, PhotoCheckResult.FAIL,
                null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhotoCheck(1L, PhotoCheckType.TBM, 3, D, null, 1L, PhotoCheckResult.FAIL,
                null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
