package com.example.backend.extract.service;

import com.example.backend.permit.entity.MeasurementItem;
import com.example.backend.rule.entity.WorkType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** LLM 켜짐: LLM 값을 서버가 확인만 하고 쓴다. 사전(규칙) 추출 값은 섞지 않는다 (11/10 결정) */
@DisplayName("LLM 추출 확인 (LLM 먼저)")
class LlmExtractionVerifierTest {

    private static RuleInputExtractor extractor;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = LlmExtractionVerifierTest.class.getResourceAsStream("/extract/dictionary.json")) {
            extractor = new RuleInputExtractor(JsonMapper.builder().build().readValue(in, ExtractDictionary.class));
        }
    }

    private static RuleInputExtractor.Result verify(String text, LlmExtraction llm, List<RuleInputExtractor.ZoneRef> zones) {
        return LlmExtractionVerifier.verify(extractor.extract(text, zones), llm, extractor::measurementRule, extractor::itemAt);
    }

    private static RuleInputExtractor.Result verify(String text, LlmExtraction llm) {
        return verify(text, llm, List.of());
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
    @DisplayName("감시자: 사전이 '이야'를 잡아도 LLM 이름(고길동)을 쓴다")
    void watcherFromLlm() {
        String text = "고길동이 감시인이야. 산소 20.9%";
        assertThat(extractor.extract(text, List.of()).form()).containsEntry("watcher_name", "이야");   // 사전 추출의 한계

        RuleInputExtractor.Result r = verify(text, new LlmExtraction(List.of(m("O2", "20.9", "PCT", "산소 20.9%")),
                List.of(), "고길동", "고길동이 감시인"));

        assertThat(r.form()).containsEntry("watcher_name", "고길동");
        assertThat(value(r, MeasurementItem.O2).source()).isEqualTo("LLM");
    }

    @Test
    @DisplayName("LLM 이 못 찾은 값은 사전이 찾았어도 비운다 (사전 값을 섞지 않음 → 규칙 엔진이 누락으로 막음)")
    void noDictionaryFill() {
        String text = "산소 농도가 17.5%, 일산화탄소 12ppm";
        assertThat(extractor.extract(text, List.of()).measurements()).hasSize(2);

        RuleInputExtractor.Result r = verify(text, llm(m("O2", "17.5", "PCT", "산소 농도가 17.5%")));

        assertThat(value(r, MeasurementItem.O2).value()).isEqualByComparingTo("17.5");
        assertThat(value(r, MeasurementItem.CO)).isNull();
    }

    @Test
    @DisplayName("문장에 없는 값·근거는 버리고 notes")
    void invented() {
        RuleInputExtractor.Result r = verify("산소 측정해 보니 낮게 나와서 17.0%", llm(
                m("O2", "18", "PCT", "산소 18"), m("CO", "30", "PPM", "낮게 나와서 17.0%")));

        assertThat(r.measurements()).isEmpty();
        assertThat(r.notes()).anyMatch(n -> n.startsWith("O2: 찾은 값(18)이 문장에 그대로 없어"))
                .anyMatch(n -> n.startsWith("CO: 찾은 값(30)이 문장에 그대로 없어"));
    }

    @Test
    @DisplayName("근거 문구의 숫자 표기가 달라도 같은 값이면 쓴다 (17 ↔ 17.0)")
    void sameNumber() {
        RuleInputExtractor.Result r = verify("산소 측정해 보니 낮게 나와서 17.0%", llm(m("O2", "17", "PCT", "낮게 나와서 17.0%")));

        assertThat(value(r, MeasurementItem.O2).value()).isEqualByComparingTo("17");
    }

    @Test
    @DisplayName("단위: 항목 단위가 아니거나 문장 단위와 다르면 버림 (황화수소 5% 를 ppm 으로 읽은 경우)")
    void units() {
        RuleInputExtractor.Result r = verify("황화수소 5% 나옴, 산소 20.9", llm(
                m("H2S", "5", "PPM", "황화수소 5%"), m("O2", "20.9", "PPM", "산소 20.9")));

        assertThat(r.measurements()).isEmpty();
        assertThat(r.notes()).anyMatch(n -> n.startsWith("H2S: 단위(PPM)")).anyMatch(n -> n.startsWith("O2: 단위(PPM)"));
        assertThat(LlmExtractionVerifier.unitConflicts("가연성 가스 0%", "PCT_LEL")).isFalse();
    }

    @Test
    @DisplayName("측정 범위 밖, 같은 항목 값 둘 이상은 비움")
    void rangeAndDuplicate() {
        RuleInputExtractor.Result r = verify("산소 120% 재측정 산소 20.9% 다시 20.5%", llm(
                m("O2", "120", "PCT", "산소 120%"), m("O2", "20.9", "PCT", "산소 20.9%"), m("O2", "20.5", "PCT", "다시 20.5%")));

        assertThat(value(r, MeasurementItem.O2)).isNull();
        assertThat(r.notes()).anyMatch(n -> n.contains("측정 범위를 벗어나")).anyMatch(n -> n.startsWith("O2: 값이 둘 이상입니다"));
    }

    @Test
    @DisplayName("작업 종류는 근거가 문장에 있을 때만, 구역은 현장 구역 대조 결과를 그대로")
    void workTypesAndZone() {
        String text = "R-201 탱크 안에서 용접합니다";
        RuleInputExtractor.Result r = verify(text, new LlmExtraction(List.of(),
                        List.of(new LlmExtraction.WorkTypeHit("HOT", "용접"), new LlmExtraction.WorkTypeHit("CONFINED", "탱크 안"),
                                new LlmExtraction.WorkTypeHit("HEIGHT", "비계 위")), null, null),
                List.of(new RuleInputExtractor.ZoneRef(7L, "R-201", "반응기 구역")));

        assertThat(r.workTypes()).extracting(RuleInputExtractor.WorkTypeHit::type).containsExactly(WorkType.HOT, WorkType.CONFINED);
        assertThat(r.zoneId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("감시자 이름이 문장에 없으면 비우고 notes")
    void watcherNotInText() {
        RuleInputExtractor.Result r = verify("감시인은 아직 미정", new LlmExtraction(List.of(), List.of(), "김철수", null));

        assertThat(r.form()).doesNotContainKey("watcher_name");
        assertThat(r.notes()).anyMatch(n -> n.startsWith("감시자: 찾은 이름(김철수)"));
    }

    @Test
    @DisplayName("항목을 바꿔 읽은 값은 버림: 산소 20.9 를 CO2 로 (11/11 0.6B 측정에서 나온 경우)")
    void wrongItem() {
        String text = "산소 20.9% 황화수소 3ppm 일산화탄소 5ppm";
        RuleInputExtractor.Result r = verify(text, llm(
                m("CO2", "20.9", "PCT", "산소 20.9%"), m("H2S", "3", "PPM", "황화수소 3ppm"), m("CO2", "5", "PPM", "일산화탄소 5ppm")));

        assertThat(value(r, MeasurementItem.CO2)).isNull();
        assertThat(value(r, MeasurementItem.H2S).value()).isEqualByComparingTo("3");
        assertThat(r.notes()).anyMatch(n -> n.startsWith("CO2: 값 20.9은(는) 문장에서 O2 값이라"))
                .anyMatch(n -> n.startsWith("CO2: 값 5은(는) 문장에서 CO 값이라"));
    }

    @Test
    @DisplayName("CO 와 CO2 를 구분한다 (CO2 안의 CO 를 일산화탄소로 보지 않음)")
    void coAndCo2() {
        String text = "CO2 0.5% CO 30ppm";
        RuleInputExtractor.Result ok = verify(text, llm(m("CO2", "0.5", "PCT", "CO2 0.5%"), m("CO", "30", "PPM", "CO 30ppm")));
        assertThat(value(ok, MeasurementItem.CO2).value()).isEqualByComparingTo("0.5");
        assertThat(value(ok, MeasurementItem.CO).value()).isEqualByComparingTo("30");

        RuleInputExtractor.Result swapped = verify(text, llm(m("CO", "0.5", "PPM", "CO2 0.5%")));
        assertThat(swapped.measurements()).isEmpty();
        assertThat(swapped.notes()).anyMatch(n -> n.startsWith("CO: 값 0.5은(는) 문장에서 CO2 값이라"));
    }

    @Test
    @DisplayName("근거가 여러 항목에 걸쳐 있어도 숫자 바로 앞 이름으로 항목을 본다")
    void longEvidence() {
        String text = "산소 20.9% 황화수소 3ppm";
        RuleInputExtractor.Result r = verify(text, llm(m("O2", "20.9", "PCT", text), m("H2S", "3", "PPM", text)));

        assertThat(value(r, MeasurementItem.O2).value()).isEqualByComparingTo("20.9");
        assertThat(value(r, MeasurementItem.H2S).value()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("숫자 앞뒤에 항목 이름이 없으면 막지 않는다 (지금처럼 단위·범위만 확인)")
    void noNameNearby() {
        RuleInputExtractor.Result r = verify("측정 결과 20.9% 정상", llm(m("O2", "20.9", "PCT", "20.9%")));

        assertThat(value(r, MeasurementItem.O2).value()).isEqualByComparingTo("20.9");
    }

    @Test
    @DisplayName("이름이 숫자 뒤에만 있으면 뒤의 이름으로 본다 (20.9% 산소)")
    void nameAfterNumber() {
        RuleInputExtractor.Result r = verify("20.9% 산소 확인", llm(m("O2", "20.9", "PCT", "20.9% 산소"), m("CO2", "20.9", "PCT", "20.9%")));

        assertThat(value(r, MeasurementItem.O2).value()).isEqualByComparingTo("20.9");
        assertThat(value(r, MeasurementItem.CO2)).isNull();
    }
}
