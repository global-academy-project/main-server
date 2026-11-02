package com.example.backend.extract.service;

import com.example.backend.rule.entity.WorkType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추출 정답 문항 (계획서 7-4장 "문장 → 기대 추출값", 판정 정확도와 따로 잰다).
 * 문항: src/test/resources/extract/gold-v1.json. 문항마다 작업 종류·구역·측정값·감시자·notes 대상 항목을 비교한다.
 */
@DisplayName("문장 → 규칙 입력 추출: 정답 문항")
class RuleInputExtractorGoldTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<RuleInputExtractor.ZoneRef> ZONES = List.of(
            new RuleInputExtractor.ZoneRef(1L, "R-201", "반응기 하부"),
            new RuleInputExtractor.ZoneRef(2L, "T-101", "저장탱크"));

    private static RuleInputExtractor extractor() throws Exception {
        try (InputStream in = RuleInputExtractorGoldTest.class.getResourceAsStream("/extract/dictionary.json")) {
            return new RuleInputExtractor(JSON.readValue(in, ExtractDictionary.class));
        }
    }

    @Test
    @DisplayName("정답 문항 전부 일치 (틀린 문항은 번호와 차이를 보여 준다)")
    void goldSet() throws Exception {
        RuleInputExtractor x = extractor();
        JsonNode cases;
        try (InputStream in = getClass().getResourceAsStream("/extract/gold-v1.json")) {
            cases = JSON.readTree(in);
        }
        List<String> wrong = new ArrayList<>();
        for (JsonNode c : cases) {
            RuleInputExtractor.Result r = x.extract(c.path("text").asString(), ZONES);

            Set<WorkType> types = r.workTypes().stream().map(RuleInputExtractor.WorkTypeHit::type).collect(Collectors.toSet());
            Set<WorkType> expectedTypes = new HashSet<>();
            c.path("workTypes").forEach(t -> expectedTypes.add(WorkType.valueOf(t.asString())));

            Map<String, BigDecimal> values = new TreeMap<>();
            r.measurements().forEach(v -> values.put(v.item().name(), v.value().stripTrailingZeros()));
            Map<String, BigDecimal> expectedValues = new TreeMap<>();
            c.path("measurement").properties().forEach(e -> expectedValues.put(e.getKey(), e.getValue().decimalValue().stripTrailingZeros()));

            Long expectedZone = c.path("zone").isString() ? ZONES.stream()
                    .filter(z -> z.code().equals(c.path("zone").asString())).findFirst().orElseThrow().id() : null;
            Object watcher = r.form().get("watcher_name");
            String expectedWatcher = c.path("form").path("watcher_name").isString() ? c.path("form").path("watcher_name").asString() : null;

            Set<String> noteItems = new HashSet<>();
            c.path("notes").forEach(n -> noteItems.add(n.asString()));
            Set<String> actualNoteItems = r.notes().stream().map(n -> n.split(":")[0]).filter(s -> !s.contains(" "))
                    .collect(Collectors.toSet());

            List<String> diff = new ArrayList<>();
            if (!types.equals(expectedTypes)) diff.add("작업 종류 " + types + " ≠ " + expectedTypes);
            if (!values.equals(expectedValues)) diff.add("측정값 " + values + " ≠ " + expectedValues);
            if (!java.util.Objects.equals(r.zoneId(), expectedZone)) diff.add("구역 " + r.zoneId() + " ≠ " + expectedZone);
            if (!java.util.Objects.equals(watcher, expectedWatcher)) diff.add("감시자 " + watcher + " ≠ " + expectedWatcher);
            if (!actualNoteItems.equals(noteItems)) diff.add("notes " + actualNoteItems + " ≠ " + noteItems);
            if (!diff.isEmpty()) {
                wrong.add("#" + c.path("id").asInt() + " " + c.path("text").asString() + " → " + diff);
            }
        }
        System.out.printf("추출 정답 문항: %d/%d 일치%n", cases.size() - wrong.size(), cases.size());
        assertThat(wrong).isEmpty();
    }

    @Test
    @DisplayName("근거 문구와 단위: 원문 그대로, 단위는 규칙 단위(%·ppm·%LEL)")
    void evidenceAndUnit() throws Exception {
        RuleInputExtractor.Result r = extractor().extract("탱크 안에서 용접할 건데 산소 농도가 17.5%야", ZONES);

        assertThat(r.measurements()).singleElement().satisfies(v -> {
            assertThat(v.evidence()).isEqualTo("산소 농도가 17.5%");
            assertThat(v.unit()).isEqualTo("PCT");
            assertThat(v.value()).isEqualByComparingTo("17.5");
        });
        assertThat(r.text()).isEqualTo("탱크 안에서 용접할 건데 산소 농도가 17.5%야");
    }
}
