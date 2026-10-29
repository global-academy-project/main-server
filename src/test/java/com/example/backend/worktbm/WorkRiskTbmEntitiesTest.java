package com.example.backend.worktbm;

import com.example.backend.rule.entity.WorkType;
import com.example.backend.risk.entity.*;
import com.example.backend.site.entity.ChemicalUnit;
import com.example.backend.tbm.entity.TbmRecord;
import com.example.backend.tbm.entity.TbmStatus;
import com.example.backend.work.entity.DetectedBy;
import com.example.backend.work.entity.WorkMaterial;
import com.example.backend.work.entity.WorkRiskFactor;
import com.example.backend.work.entity.WorkRole;
import com.example.backend.work.entity.WorkWorker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 작업·위험성평가·TBM 엔티티가 DB 규칙(CHECK·트리거)을 저장 전에 먼저 막는지 (DB 없이) */
@DisplayName("작업·위험성평가·TBM 엔티티 검증")
class WorkRiskTbmEntitiesTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 29);

    @Test
    @DisplayName("고위험 요인: GENERAL 은 넣을 수 없고, 찾은 주체 기본값은 AGENT")
    void riskFactor() {
        assertThat(new WorkRiskFactor(1L, WorkType.HOT, null).getDetectedBy()).isEqualTo(DetectedBy.AGENT);
        assertThatThrownBy(() -> new WorkRiskFactor(1L, WorkType.GENERAL, DetectedBy.USER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("작업 물질: CAS 형식, 양·단위 함께")
    void material() {
        new WorkMaterial(1L, "108-88-3", "톨루엔", new BigDecimal("20"), ChemicalUnit.L);
        assertThatThrownBy(() -> new WorkMaterial(1L, "abc", "톨루엔", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkMaterial(1L, null, "톨루엔", null, ChemicalUnit.L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("작업 배치: 역할 기본값 WORKER")
    void workWorker() {
        assertThat(new WorkWorker(1L, 2L, 3L, null).getRoleInWork()).isEqualTo(WorkRole.WORKER);
    }

    @Test
    @DisplayName("위험성평가: 일일은 작업 필수, 정기·수시는 이름 필수, 참고는 일일만")
    void assessmentRules() {
        assertThatThrownBy(() -> RiskAssessment.daily(1L, null, D, 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiskAssessment.periodic(1L, AssessmentType.REGULAR, 1L, " ", D, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiskAssessment.periodic(1L, AssessmentType.DAILY, 1L, "x", D, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        RiskAssessment regular = RiskAssessment.periodic(1L, AssessmentType.REGULAR, 1L, "정기 평가", D, 1L);
        assertThatThrownBy(() -> regular.referBase(9L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("위험성평가: 제출하면 수정 불가, 취소하면 다시 수정 가능, 새 판은 revision + 1")
    void assessmentFlow() {
        RiskAssessment a = RiskAssessment.daily(1L, 10L, D, 1L);
        a.submit();
        assertThat(a.getStatus()).isEqualTo(RiskStatus.PENDING);
        assertThatThrownBy(() -> a.updateContent(null, null, D, RiskMethod.CHECKLIST, "v1", null))
                .isInstanceOf(IllegalStateException.class);
        a.withdraw();
        a.updateContent(null, null, D, RiskMethod.CHECKLIST, "v1", "{}");
        a.submit();
        a.reject();
        assertThatThrownBy(a::approve).isInstanceOf(IllegalStateException.class);

        RiskAssessment next = a.newRevision(2L);
        assertThat(next.getRevision()).isEqualTo(2);
        assertThat(next.getStatus()).isEqualTo(RiskStatus.DRAFT);
        assertThat(next.getMethod()).isEqualTo(RiskMethod.CHECKLIST);
        assertThat(next.getWriterId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("위험요인: 빈도·강도 1~5, 개선 후 값은 둘 다 있거나 둘 다 없음")
    void item() {
        RiskAssessmentItem i = new RiskAssessmentItem(1L, 1, "용접", "불티 비산", "FIRE", 3, 4,
                RiskLevel.HIGH, "불티 방지포", 1, 4);
        assertThat(i.score()).isEqualTo(12);
        assertThatThrownBy(() -> new RiskAssessmentItem(1L, 1, "용접", "불티", null, 6, 1, RiskLevel.LOW, "x", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiskAssessmentItem(1L, 1, "용접", "불티", null, 1, 1, RiskLevel.LOW, "x", 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiskAssessmentItem(1L, 0, "용접", "불티", null, 1, 1, RiskLevel.LOW, "x", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TBM: 실시 완료는 시각·인원 필수, 승인은 완료 후만, 승인되면 수정 불가")
    void tbmFlow() {
        TbmRecord t = new TbmRecord(1L, D, null, null, 1L);
        assertThatThrownBy(t::approve).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> t.complete(null, 3)).isInstanceOf(IllegalArgumentException.class);
        t.complete(LocalDateTime.of(D, java.time.LocalTime.of(7, 50)), 5);
        assertThat(t.getStatus()).isEqualTo(TbmStatus.DONE);
        t.approve();
        assertThat(t.isEditable()).isFalse();
        assertThatThrownBy(() -> t.updateContent(null, null, D, "반장", null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(t::reopen).isInstanceOf(IllegalStateException.class);
    }
}
