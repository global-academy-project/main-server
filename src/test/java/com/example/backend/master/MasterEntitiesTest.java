package com.example.backend.master;

import com.example.backend.site.entity.*;
import com.example.backend.worker.entity.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 기준 정보 엔티티가 DB CHECK 규칙을 저장 전에 먼저 막는지 (DB 없이) */
@DisplayName("기준 정보 엔티티 검증")
class MasterEntitiesTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 29);

    @Test
    @DisplayName("현장: 기본 설정값과 운영 설정 범위")
    void siteSettings() {
        Site site = new Site(" 울산공장 ", null);
        assertThat(site.getName()).isEqualTo("울산공장");
        assertThat(site.getMode()).isEqualTo(SiteMode.VERIFY);
        assertThat(site.getTbmDeadline()).isEqualTo(LocalTime.of(9, 0));
        assertThat(site.getApprovalDueMinutes()).isEqualTo(60);

        site.updateSettings(LocalTime.of(8, 30), LocalTime.of(17, 0), 30, 90);
        assertThat(site.getNearMissWarnDays()).isEqualTo(90);

        assertThatThrownBy(() -> site.updateSettings(LocalTime.of(18, 0), LocalTime.of(18, 0), 30, 30))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> site.updateSettings(LocalTime.of(8, 0), LocalTime.of(18, 0), 4, 30))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> site.updateSettings(LocalTime.of(8, 0), LocalTime.of(18, 0), 60, 366))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> site.updateProfile("화학", -1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Site(" ", SiteMode.GUIDE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("설비: 점검(교정)일 지남 여부")
    void equipmentOverdue() {
        Equipment e = new Equipment(1L, null, "GD-01", "복합가스측정기", "GAS_DETECTOR", D);
        assertThat(e.isInspectionOverdue(D)).isFalse();
        assertThat(e.isInspectionOverdue(D.plusDays(1))).isTrue();
        assertThat(new Equipment(1L, null, "CR-01", "크레인", "CRANE", null).isInspectionOverdue(D)).isFalse();
    }

    @Test
    @DisplayName("화학물질: CAS 형식, 보관량·단위는 함께")
    void chemical() {
        new SiteChemical(1L, null, "108-88-3", "톨루엔", new BigDecimal("200.000"), ChemicalUnit.L, D);
        new SiteChemical(1L, 2L, null, "혼합 시너", null, null, null);
        assertThatThrownBy(() -> new SiteChemical(1L, null, "10888-3", "톨루엔", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SiteChemical(1L, null, null, "톨루엔", BigDecimal.ONE, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SiteChemical(1L, null, null, "톨루엔", new BigDecimal("-1"), ChemicalUnit.KG, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("팀: 사업자등록번호는 외부 업체만, 숫자 10자리 (하이픈 제거)")
    void team() {
        assertThat(Team.external("대한배관", "123-45-67890").getBusinessNo()).isEqualTo("1234567890");
        assertThat(Team.internal("설비팀").getBusinessNo()).isNull();
        assertThatThrownBy(() -> new Team("설비팀", TeamType.INTERNAL, "1234567890"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Team.external("대한배관", "12345")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("작업자: 전화번호 형식 (하이픈 제거), 비활성")
    void worker() {
        Worker w = new Worker(null, "김용접", "010-1234-5678", "용접", null);
        assertThat(w.getPhone()).isEqualTo("01012345678");
        assertThat(w.getEmploymentType()).isEqualTo(EmploymentType.DIRECT);
        w.deactivate();
        assertThat(w.isActive()).isFalse();
        assertThatThrownBy(() -> new Worker(null, "김용접", "1012345678", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("현장 배정: 떠나면 비활성, 다시 배정하면 떠난 날 지움")
    void workerSite() {
        WorkerSite ws = new WorkerSite(1L, 1L, D);
        assertThatThrownBy(() -> ws.leave(D.minusDays(1))).isInstanceOf(IllegalArgumentException.class);
        ws.leave(D.plusDays(10));
        assertThat(ws.isActive()).isFalse();
        ws.rejoin(D.plusDays(20));
        assertThat(ws.isActive()).isTrue();
        assertThat(ws.getAssignedTo()).isNull();
    }

    @Test
    @DisplayName("자격: 코드 형식, 만료일, 유효 여부 (만료일 당일까지 유효)")
    void qualification() {
        WorkerQualification q = new WorkerQualification(1L, QualificationType.TRAINING, "CONFINED_SPACE_SPECIAL",
                "밀폐공간 특별교육", D, D.plusYears(1), "안전보건공단", null, 1L);
        assertThat(q.isValidOn(D)).isTrue();
        assertThat(q.isValidOn(D.plusYears(1))).isTrue();
        assertThat(q.isValidOn(D.plusYears(1).plusDays(1))).isFalse();
        assertThat(q.isValidOn(D.minusDays(1))).isFalse();
        assertThatThrownBy(() -> new WorkerQualification(1L, QualificationType.LICENSE, "welding",
                "용접", D, null, null, null, 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerQualification(1L, QualificationType.LICENSE, "WELDING",
                "용접", D, D.minusDays(1), null, null, 1L)).isInstanceOf(IllegalArgumentException.class);
    }
}
