package com.example.backend.nearmiss.kb;

import com.example.backend.tag.entity.TagAxis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 아차사고 → kb_accidents 청크 (A 적재 계약 11/04) */
@DisplayName("아차사고 RAG 청크")
class NearMissKbDocumentsTest {

    @Test
    @DisplayName("point_id 는 Python uuid5(NAMESPACE_URL, 'NEAR_MISS:{id}:0') 와 같은 값")
    void pointId() {
        // python -c "import uuid;print(uuid.uuid5(uuid.NAMESPACE_URL,'NEAR_MISS:12:0'))"
        assertThat(NearMissKbDocuments.pointId(12L)).isEqualTo("d91aab42-939e-5a48-9ff5-e915506f3736");
        assertThat(NearMissKbDocuments.pointId(1L)).isEqualTo("f0dbe59a-76bb-5823-9aae-218a86b486b2");
        assertThat(Uuid5.of(Uuid5.NAMESPACE_URL, "NEAR_MISS:12:0").version()).isEqualTo(5);
    }

    @Test
    @DisplayName("payload: 필수 5개, 최상위 태그(물질은 cas), year·locator·text, 문장은 태그 이름으로")
    void build() {
        KbIngestClient.Chunk c = NearMissKbDocuments.build(12L, 3L, 5L, "R-201 배관 랙",
                LocalDateTime.of(2026, 11, 3, 10, 20, 0, 123), " 배관 랙 발판 미끄러짐 ", "점검 중 발판이 미끄러져 넘어질 뻔함", null,
                Map.of(TagAxis.SEVERITY, List.of("NEAR_MISS"), TagAxis.ACCIDENT_TYPE, List.of("FALL"),
                        TagAxis.WORK_TYPE, List.of("HEIGHT"), TagAxis.SUBSTANCE, List.of("108-88-3")),
                Map.of("SEVERITY:NEAR_MISS", "아차사고", "ACCIDENT_TYPE:FALL", "떨어짐", "WORK_TYPE:HEIGHT", "고소"));

        assertThat(c.pointId()).isEqualTo("d91aab42-939e-5a48-9ff5-e915506f3736");
        Map<String, Object> p = c.payload();
        assertThat(p).containsEntry("kb_source_id", 12L).containsEntry("kb_chunk_id", 12L)
                .containsEntry("source_type", "NEAR_MISS").containsEntry("site_id", 3L).containsEntry("flagged", false)
                .containsEntry("severity", List.of("NEAR_MISS")).containsEntry("accident_type", List.of("FALL"))
                .containsEntry("hazard", List.of()).containsEntry("work_type", List.of("HEIGHT"))
                .containsEntry("cas", List.of("108-88-3")).containsEntry("ghs", List.of())
                .containsEntry("title", "배관 랙 발판 미끄러짐").containsEntry("locator", "아차사고 #12")
                .containsEntry("year", 2026).containsEntry("occurred_at", "2026-11-03T10:20").containsEntry("zone_id", 5L);
        assertThat(p).doesNotContainKey("tags");
        assertThat(c.text()).isEqualTo("배관 랙 발판 미끄러짐. 점검 중 발판이 미끄러져 넘어질 뻔함 장소: R-201 배관 랙."
                + " 발생형태: 떨어짐. 작업: 고소. 관련 물질(CAS): 108-88-3. 중대도: 아차사고.");
        assertThat(p.get("text")).isEqualTo(c.text());
    }
}
