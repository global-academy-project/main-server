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
    @DisplayName("단위: 원문에 다른 단위가 적혀 있으면 버리고, 단위가 없으면 항목 단위로 채우고 알린다 (LLM 이 준 단위는 보지 않음)")
    void units() {
        RuleInputExtractor.Result r = verify("황화수소 5% 나옴, 산소 20.9", llm(
                m("H2S", "5", "PPM", "황화수소 5%"), m("O2", "20.9", "PPM", "산소 20.9")));

        assertThat(value(r, MeasurementItem.H2S)).isNull();
        assertThat(value(r, MeasurementItem.O2).value()).isEqualByComparingTo("20.9");
        assertThat(value(r, MeasurementItem.O2).unit()).isEqualTo("PCT");
        assertThat(r.notes()).anyMatch(n -> n.startsWith("H2S: 단위가 %(으)로 왔습니다. 기준 단위는 ppm라서 비워 둡니다"))
                .anyMatch(n -> n.startsWith("O2: 단위가 없어 %(으)로 봤습니다. 확인해 주세요"));
    }

    @Test
    @DisplayName("단위 없이 숫자만 친 값 (현장에서 흔함): LLM 이 단위를 비워도 항목 단위로 채운다")
    void numberOnly() {
        RuleInputExtractor.Result r = verify("산소 19.5 황화수소 3 가연성가스 4", llm(
                m("O2", "19.5", null, "산소 19.5"), m("H2S", "3", null, "황화수소 3"), m("LEL", "4", null, "가연성가스 4")));

        assertThat(value(r, MeasurementItem.O2).unit()).isEqualTo("PCT");
        assertThat(value(r, MeasurementItem.H2S).unit()).isEqualTo("PPM");
        assertThat(value(r, MeasurementItem.LEL).unit()).isEqualTo("PCT_LEL");
        assertThat(r.notes()).anyMatch(n -> n.startsWith("H2S: 단위가 없어 ppm(으)로 봤습니다"))
                .anyMatch(n -> n.startsWith("LEL: 단위가 없어 %LEL(으)로 봤습니다"));
    }

    @Test
    @DisplayName("단위 표기: 숫자 바로 뒤를 본다 (5% LEL·LEL 3%·피피엠·프로)")
    void unitWords() {
        assertThat(LlmExtractionVerifier.unitAfter("가연성가스 5% LEL", 7)).isEqualTo("PCT_LEL");
        assertThat(LlmExtractionVerifier.unitAfter("인화성 가스 LEL 3%", 12)).isEqualTo("PCT");
        assertThat(LlmExtractionVerifier.unitAfter("일산화탄소 30 피피엠", 8)).isEqualTo("PPM");
        assertThat(LlmExtractionVerifier.unitAfter("탄산가스 1.5프로", 8)).isEqualTo("PCT");
        assertThat(LlmExtractionVerifier.unitAfter("산소 19.5 황화수소 3ppm", 7)).isNull();   // 뒤의 다른 항목 단위는 보지 않음
    }

    @Test
    @DisplayName("측정 범위 밖, 같은 항목 값 둘 이상은 비움")
    void rangeAndDuplicate() {
        RuleInputExtractor.Result r = verify("산소 120% 재측정 산소 20.9% 다시 20.5%", llm(
                m("O2", "120", "PCT", "산소 120%"), m("O2", "20.9", "PCT", "산소 20.9%"), m("O2", "20.5", "PCT", "다시 20.5%")));

        assertThat(value(r, MeasurementItem.O2)).isNull();
        assertThat(r.notes()).anyMatch(n -> n.contains("측정 범위를 벗어나"))
                .anyMatch(n -> n.startsWith("O2: 값이 둘 이상입니다 (20.9, 20.5). 하나만 적어 주세요"));
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

    @Test
    @DisplayName("영문 약어 안의 숫자는 값이 아니다 (CO2=2% 의 2 는 = 뒤의 2)")
    void numberInsideAbbreviation() {
        RuleInputExtractor.Result r = verify("CO2=2프로, H2S: 3피피엠", llm(m("CO2", "2", "PCT", "CO2=2프로"), m("H2S", "3", "PPM", "H2S: 3피피엠")));

        assertThat(value(r, MeasurementItem.CO2).value()).isEqualByComparingTo("2");
        assertThat(value(r, MeasurementItem.H2S).value()).isEqualByComparingTo("3");
        assertThat(LlmExtractionVerifier.containsNumber("H2S 3ppm", new BigDecimal("2"))).isFalse();
    }

    @Test
    @DisplayName("어순: 값이 이름보다 먼저인 조각이 끊는 기호로 이어지면 뒤 이름으로 본다")
    void valueBeforeName() {
        RuleInputExtractor.Result r = verify("19% 산소 / 9%LEL 가연성가스", llm(
                m("O2", "19", "PCT", "19% 산소"), m("LEL", "9", "PCT_LEL", "9%LEL 가연성가스")));
        assertThat(value(r, MeasurementItem.O2).value()).isEqualByComparingTo("19");
        assertThat(value(r, MeasurementItem.LEL).value()).isEqualByComparingTo("9");

        RuleInputExtractor.Result r2 = verify("20.8% 산소, 0ppm 황화수소", llm(
                m("O2", "20.8", "PCT", "20.8% 산소"), m("H2S", "0", "PPM", "0ppm 황화수소")));
        assertThat(value(r2, MeasurementItem.H2S).value()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("어순: 뒤 이름이 자기 값을 가지면 쓰지 않는다 (산소, 20.9% 황화수소 3ppm 의 20.9 는 산소)")
    void followingNameWithOwnValue() {
        String text = "산소, 20.9% 황화수소 3ppm";
        RuleInputExtractor.Result ok = verify(text, llm(m("O2", "20.9", "PCT", "20.9%"), m("H2S", "3", "PPM", "황화수소 3ppm")));
        assertThat(value(ok, MeasurementItem.O2).value()).isEqualByComparingTo("20.9");

        RuleInputExtractor.Result wrong = verify(text, llm(m("H2S", "20.9", "PCT", "20.9%")));
        assertThat(wrong.measurements()).isEmpty();   // 20.9 를 황화수소로 받아들이지 않는다
        assertThat(wrong.notes()).anyMatch(n -> n.startsWith("H2S: 값 20.9은(는) 문장에서 O2 값이라"));

        RuleInputExtractor.Result plain = verify("산소 20.9%황화수소 3ppm", llm(m("O2", "20.9", "PCT", "산소 20.9%")));
        assertThat(value(plain, MeasurementItem.O2).value()).isEqualByComparingTo("20.9");   // 끊는 기호 없음 → 앞 이름
    }

    @Test
    @DisplayName("어순: 앞 이름과 사이에 숫자가 있으면 재측정 값으로 보고 앞 이름 그대로 (값 두 번 → 하나만 적어 주세요)")
    void remeasureAfterComma() {
        RuleInputExtractor.Result r = verify("산소 16.9 %, 재측정 23퍼센트. 일산화탄소 측정값 2ppm", llm(
                m("O2", "16.9", "PCT", "산소 16.9 %"), m("O2", "23", "PCT", "재측정 23퍼센트"), m("CO", "2", "PPM", "일산화탄소 측정값 2ppm")));

        assertThat(value(r, MeasurementItem.O2)).isNull();   // 16.9 하나만 남아 그대로 들어가면 안 된다
        assertThat(r.notes()).anyMatch(n -> n.startsWith("O2: 값이 둘 이상입니다 (16.9, 23)"));
        assertThat(value(r, MeasurementItem.CO).value()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("어순: 뒤 이름이 자기 조각 안에 값을 가지면 쓰지 않는다 (산소, 20.9%. 이산화탄소 측정값 0.5%)")
    void followingNameWithValueLater() {
        RuleInputExtractor.Result r = verify("산소, 20.9%. 이산화탄소 측정값 0.5%", llm(m("CO2", "20.9", "PCT", "20.9%")));

        assertThat(r.measurements()).isEmpty();
    }
}
