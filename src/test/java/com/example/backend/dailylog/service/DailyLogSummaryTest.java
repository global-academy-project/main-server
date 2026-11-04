package com.example.backend.dailylog.service;

import com.example.backend.dashboard.dto.DashboardResponse;
import com.example.backend.dashboard.dto.IncompleteType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 안전일지 형식 요약: 숫자는 집계 그대로, 개선조치·승인 지연은 미완료 목록에 섞지 않음 */
@DisplayName("안전일지 형식 요약")
class DailyLogSummaryTest {

    private static DashboardResponse.Item item(IncompleteType type, int count) {
        return new DashboardResponse.Item(type, type.label(), count, false, List.of());
    }

    @Test
    @DisplayName("작업·허가·TBM·점검표·아차사고·미완료·개선조치를 정해진 순서로 쓴다")
    void full() {
        var c = new DailyLogAggregator.Counts(5, 1, 3, 2, 1, 0, 4, 3, 1, 2, 2, 1, 1);
        String s = DailyLogAggregator.summarize(LocalDate.of(2026, 11, 3), c,
                List.of(item(IncompleteType.MEASUREMENT_MISSING, 1), item(IncompleteType.CORRECTIVE_OPEN, 2)));
        assertThat(s).isEqualTo("11월 3일 작업 5건(차단 1건). 작업허가 3건 중 승인 2건, 승인 대기 1건, 차단 0건."
                + " TBM 3건 실시, 미실시 작업 1건. 점검표 2건. 아차사고 1건 보고. 미완료: 가스 측정값 미입력 1건."
                + " 미조치 개선조치 2건(기한 지남 1건).");
    }

    @Test
    @DisplayName("작업이 없는 날")
    void noWork() {
        var c = new DailyLogAggregator.Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(DailyLogAggregator.summarize(LocalDate.of(2026, 11, 3), c, List.of())).isEqualTo("11월 3일 등록된 작업이 없습니다.");
    }
}
