package com.example.backend.extract.service;

import com.example.backend.permit.entity.MeasurementItem;
import com.example.backend.rule.entity.WorkType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 규칙 추출 + LLM 추출 합치기: LLM 값은 원문 근거·단위·규칙 추출과 맞을 때만 쓴다 */
@DisplayName("추출 합치기 (규칙 + LLM)")
class ExtractionMergerTest {

    private static RuleInputExtractor.Result rule(String text) throws Exception {
        try (InputStream in = ExtractionMergerTest.class.getResourceAsStream("/extract/dictionary.json")) {
            return new RuleInputExtractor(JsonMapper.builder().build().readValue(in, ExtractDictionary.class))
                    .extract(text, List.of());
        }
    }

    private static LlmExtraction.Measurement m(String item, String value, String unit, String evidence) {
        return new LlmExtraction.Measurement(item, new BigDecimal(value), unit, evidence);
    }

    private static LlmExtraction llm(LlmExtraction.Measurement... ms) {
        return new LlmExtraction(List.of(ms), List.of(), null, null);
    }

    private static RuleInputExtractor.Value value(RuleInputExtractor.Result r, MeasurementItem item) {
        return r.measurements().stream().filter(v -> v.item() == item).findFirst().orElse(null);
    }

    @Test
    @DisplayName("같은 값이면 BOTH, 다르면 둘 다 비우고 notes")
    void agreeAndDisagree() throws Exception {
        RuleInputExtractor.Result base = rule("산소 농도가 17.5%, 일산화탄소 12ppm, 재측정 15");
        assertThat(value(base, MeasurementItem.CO).value()).isEqualByComparingTo("12");

        RuleInputExtractor.Result merged = ExtractionMerger.merge(base, llm(
                m("O2", "17.5", "PCT", "산소 농도가 17.5%"), m("CO", "15", "PPM", "재측정 15")));

        assertThat(value(merged, MeasurementItem.O2).source()).isEqualTo("BOTH");
        assertThat(value(merged, MeasurementItem.CO)).isNull();
        assertThat(merged.notes()).anyMatch(n -> n.startsWith("CO: 규칙 추출(12)과 LLM 추출(15)"));

        RuleInputExtractor.Result invented = ExtractionMerger.merge(base, llm(m("CO", "30", "PPM", "일산화탄소 12ppm")));
        assertThat(value(invented, MeasurementItem.CO).value()).isEqualByComparingTo("12");   // 근거에 없는 30 은 버림
    }

    @Test
    @DisplayName("규칙이 놓친 값: 원문 근거가 있으면 LLM 값으로 채우고 확인 문구, 근거·단위가 틀리면 버림")
    void llmOnly() throws Exception {
        RuleInputExtractor.Result base = rule("산소 측정해 보니까 생각보다 많이 낮게 나와서 17.0%");
        assertThat(base.measurements()).isEmpty();

        RuleInputExtractor.Result ok = ExtractionMerger.merge(base, llm(m("O2", "17", "PCT", "낮게 나와서 17.0%")));
        assertThat(value(ok, MeasurementItem.O2)).satisfies(v -> {
            assertThat(v.source()).isEqualTo("LLM");
            assertThat(v.value()).isEqualByComparingTo("17");
        });
        assertThat(ok.notes()).anyMatch(n -> n.startsWith("O2: LLM 으로 찾은 값"));

        assertThat(value(ExtractionMerger.merge(base, llm(m("O2", "18", "PCT", "산소 18"))), MeasurementItem.O2)).isNull();
        assertThat(value(ExtractionMerger.merge(base, llm(m("O2", "17", "PPM", "낮게 나와서 17.0%"))), MeasurementItem.O2)).isNull();
    }

    @Test
    @DisplayName("규칙 추출이 문제를 남긴 항목(단위 불일치)은 LLM 값으로 채우지 않는다")
    void troubledItemStaysEmpty() throws Exception {
        RuleInputExtractor.Result base = rule("황화수소 5% 나옴");

        RuleInputExtractor.Result merged = ExtractionMerger.merge(base, llm(m("H2S", "5", "PPM", "황화수소 5%")));

        assertThat(value(merged, MeasurementItem.H2S)).isNull();
    }

    @Test
    @DisplayName("작업 종류·감시자: 원문 근거가 있을 때만 더한다")
    void typesAndWatcher() throws Exception {
        RuleInputExtractor.Result base = rule("배관 위에서 작업, 감시는 홍길동이 맡음");
        LlmExtraction l = new LlmExtraction(List.of(),
                List.of(new LlmExtraction.WorkTypeHit("HEIGHT", "배관 위에서"), new LlmExtraction.WorkTypeHit("HOT", "불꽃")),
                "홍길동", "감시는 홍길동");

        RuleInputExtractor.Result merged = ExtractionMerger.merge(base, l);

        assertThat(merged.workTypes()).extracting(RuleInputExtractor.WorkTypeHit::type).containsExactly(WorkType.HEIGHT);
        assertThat(merged.workTypes().get(0).source()).isEqualTo("LLM");
        assertThat(merged.form()).containsEntry("watcher_name", "홍길동");
        assertThat(ExtractionMerger.merge(base, new LlmExtraction(List.of(), List.of(), "김철수", null)).form())
                .doesNotContainKey("watcher_name");
    }

    @Test
    @DisplayName("ai-model 응답 data 모양 그대로 읽어 합친다 (value 는 실수, 감시자 null)")
    void aiModelResponseShape() throws Exception {
        String data = """
                {"measurements": [{"item": "O2", "value": 17.0, "unit": "PCT", "evidence": "낮게 나와서 17.0%"}],
                 "workTypes": [{"type": "CONFINED", "evidence": "탱크 들어가는데"}],
                 "watcherName": null, "watcherEvidence": null, "extra": "무시"}
                """;
        LlmExtraction parsed = JsonMapper.builder().build().readValue(data, LlmExtraction.class);
        RuleInputExtractor.Result base = rule("R-201 탱크 들어가는데 산소 측정해 보니까 생각보다 많이 낮게 나와서 17.0%");

        RuleInputExtractor.Result merged = ExtractionMerger.merge(base, parsed);

        assertThat(value(merged, MeasurementItem.O2).value()).isEqualByComparingTo("17.0");
        assertThat(merged.workTypes()).extracting(RuleInputExtractor.WorkTypeHit::type).contains(WorkType.CONFINED);
        assertThat(merged.form()).doesNotContainKey("watcher_name");
    }
}
