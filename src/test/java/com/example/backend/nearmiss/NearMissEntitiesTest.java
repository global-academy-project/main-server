package com.example.backend.nearmiss;

import com.example.backend.corrective.entity.ActionSource;
import com.example.backend.corrective.entity.ActionStatus;
import com.example.backend.corrective.entity.CorrectiveAction;
import com.example.backend.nearmiss.entity.NearMiss;
import com.example.backend.nearmiss.entity.NearMissStatus;
import com.example.backend.tag.entity.*;
import com.example.backend.work.entity.WarningSeverity;
import com.example.backend.work.entity.WarningSource;
import com.example.backend.work.entity.WorkWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 아차사고·태그·경고·개선 조치 엔티티가 DB 규칙을 저장 전에 먼저 막는지 (DB 없이) */
@DisplayName("아차사고·태그·경고·개선 조치 엔티티 검증")
class NearMissEntitiesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 10, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();

    private static NearMiss nearMiss() {
        return new NearMiss(1L, 2L, null, NOW.minusHours(1), "사다리 미끄러짐", "고정 안 된 사다리가 밀림", null, 1L, NOW);
    }

    @Test
    @DisplayName("아차사고: 발생 시각은 보고 시각 + 5분까지 허용")
    void occurredAt() {
        new NearMiss(1L, null, null, NOW.plusMinutes(5), "t", "d", null, 1L, NOW);
        assertThatThrownBy(() -> new NearMiss(1L, null, null, NOW.plusMinutes(6), "t", "d", null, 1L, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NearMiss(1L, null, null, NOW, " ", "d", null, 1L, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("아차사고: 보고 → 태깅 → 검토 → 종결, 종결 후 수정 불가")
    void nearMissFlow() {
        NearMiss nm = nearMiss();
        assertThatThrownBy(nm::close).isInstanceOf(IllegalStateException.class);
        nm.markTagged();
        assertThat(nm.getStatus()).isEqualTo(NearMissStatus.TAGGED);
        nm.review();
        nm.markTagged();                          // 검토 후 태그를 고쳐도 상태는 그대로
        assertThat(nm.getStatus()).isEqualTo(NearMissStatus.REVIEWED);
        nm.close();
        assertThatThrownBy(() -> nm.update(null, null, NOW, "t", "d", null, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("코드표: SUBSTANCE 축과 소문자 코드는 넣을 수 없다")
    void tagCode() {
        new TagCode(TagAxis.HAZARD, "BIOLOGICAL", "생물학적", null, 6);
        assertThatThrownBy(() -> new TagCode(TagAxis.SUBSTANCE, "X", "x", null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TagCode(TagAxis.HAZARD, "bio", "x", null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("태그: 물질은 CAS 번호, 확신도 0~1, 사람 태그는 확신도 없음")
    void recordTag() {
        assertThat(RecordTag.user(RecordType.NEAR_MISS, 1L, TagAxis.ACCIDENT_TYPE, "FALL").getSource()).isEqualTo(TagSource.USER);
        RecordTag.ai(RecordType.NEAR_MISS, 1L, TagAxis.SUBSTANCE, "108-88-3", new BigDecimal("0.85"));
        assertThatThrownBy(() -> RecordTag.ai(RecordType.NEAR_MISS, 1L, TagAxis.SUBSTANCE, "톨루엔", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecordTag.ai(RecordType.NEAR_MISS, 1L, TagAxis.HAZARD, "CHEMICAL", new BigDecimal("1.2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("경고: 출처별 연결 규칙, 긴급 경고는 조치 내용 필수, 확인은 한 번만")
    void warning() {
        assertThatThrownBy(() -> WorkWarning.of(1L, 1L, WarningSource.NEAR_MISS, null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkWarning.fromNearMiss(1L, 1L, null, null, "x"))
                .isInstanceOf(IllegalArgumentException.class);

        WorkWarning critical = WorkWarning.of(1L, 1L, WarningSource.ZONE_HAZARD, WarningSeverity.CRITICAL, "가연성 가스 체류 구역");
        assertThatThrownBy(() -> critical.acknowledge(1L, NOW, " ")).isInstanceOf(IllegalArgumentException.class);
        critical.acknowledge(1L, NOW, "LEL 재측정 후 진행");
        assertThat(critical.isAcknowledged()).isTrue();
        assertThatThrownBy(() -> critical.acknowledge(1L, NOW, "again")).isInstanceOf(IllegalStateException.class);

        WorkWarning warn = WorkWarning.fromNearMiss(1L, 1L, 9L, null, "같은 구역 추락 아차사고");
        warn.acknowledge(1L, NOW, null);
        assertThat(warn.getAckNote()).isNull();
    }

    @Test
    @DisplayName("개선 조치: 기타 외에는 출처 필수, 완료는 내용 필수, 확인은 완료 후만, 기한 지남")
    void corrective() {
        assertThatThrownBy(() -> new CorrectiveAction(1L, ActionSource.NEAR_MISS, null, "t", null, 1L, TODAY, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        new CorrectiveAction(1L, ActionSource.OTHER, null, "t", null, 1L, TODAY, 1L);

        CorrectiveAction ca = new CorrectiveAction(1L, ActionSource.NEAR_MISS, 5L, "사다리 고정 장치 설치", null, 2L, TODAY, 1L);
        assertThat(ca.isOverdue(TODAY)).isFalse();
        assertThat(ca.isOverdue(TODAY.plusDays(1))).isTrue();
        assertThatThrownBy(() -> ca.verify(3L, NOW)).isInstanceOf(IllegalStateException.class);
        ca.start();
        assertThatThrownBy(() -> ca.complete(NOW, " ")).isInstanceOf(IllegalArgumentException.class);
        ca.complete(NOW, "고정 장치 설치 완료");
        ca.reopen();
        assertThat(ca.getCompletedAt()).isNull();
        ca.complete(NOW, "재설치");
        ca.verify(3L, NOW);
        assertThat(ca.getStatus()).isEqualTo(ActionStatus.VERIFIED);
        assertThat(ca.isOverdue(TODAY.plusDays(10))).isFalse();
        assertThatThrownBy(ca::cancel).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ca.update("t", null, 1L, TODAY)).isInstanceOf(IllegalStateException.class);
    }
}
