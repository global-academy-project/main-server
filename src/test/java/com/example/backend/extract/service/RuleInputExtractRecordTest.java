package com.example.backend.extract.service;

import com.example.backend.permit.entity.MeasurementItem;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.rule.service.RuleCheckService;
import com.example.backend.site.repository.WorkZoneRepository;
import com.example.backend.site.service.SiteGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 허가서 반영용 추출(11/12): LLM 을 부르지 않고 기억한 미리 보기 결과만 쓴다. 여러 문장 합치기 */
@DisplayName("반영용 추출: LLM 안 부름, 여러 문장 합치기")
class RuleInputExtractRecordTest {

    static final String TEXT = "탱크 내부 청소 산소 20.9% 황화수소 3ppm";

    /** 부른 횟수를 세는 가짜 LLM */
    static final class CountingLlm implements LlmExtractClient {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Optional<LlmExtraction> extract(String text, List<String> items, List<String> workTypes) {
            calls.incrementAndGet();
            return Optional.of(new LlmExtraction(List.of(
                    new LlmExtraction.Measurement("O2", new BigDecimal("20.9"), "PCT", "산소 20.9%"),
                    new LlmExtraction.Measurement("H2S", new BigDecimal("3"), "PPM", "황화수소 3ppm")),
                    List.of(), null, null));
        }

        @Override
        public String name() {
            return "LLM:test";
        }
    }

    final CountingLlm llm = new CountingLlm();
    final RuleInputExtractService service = service(llm);

    static RuleInputExtractService service(LlmExtractClient client) {
        try {
            return new RuleInputExtractService(new ClassPathResource("extract/dictionary.json"), JsonMapper.builder().build(),
                    mock(RuleCheckService.class), mock(WorkZoneRepository.class), mock(SiteGuard.class), client,
                    new LlmExtractCache(Clock.systemUTC(), 30));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("미리 보기 뒤 반영: 기억한 LLM 결과를 쓰고 LLM 은 한 번만 부른다")
    void usesPreviewResult() {
        service.run(TEXT, 1L);   // 미리 보기
        RuleInputExtractService.Extraction recorded = service.recorded(TEXT, 1L);

        assertThat(llm.calls).hasValue(1);
        assertThat(recorded.extractor()).isEqualTo("LLM:test");
        assertThat(recorded.result().measurements()).extracting(RuleInputExtractor.Value::item)
                .contains(MeasurementItem.O2, MeasurementItem.H2S);
    }

    @Test
    @DisplayName("기억이 없으면(30분 지남·재시작) LLM 을 부르지 않고 사전(규칙) 추출로 기록, 이유를 notes 에 남김")
    void noPreviewMeansRuleOnly() {
        RuleInputExtractService.Extraction recorded = service.recorded(TEXT, 1L);

        assertThat(llm.calls).hasValue(0);
        assertThat(recorded.extractor()).startsWith("RULE:");
        assertThat(recorded.result().notes()).anyMatch(n -> n.contains("미리 보기 때의 LLM 결과가 없어"));
    }

    @Test
    @DisplayName("여러 문장 합치기: 뒤 문장이 같은 항목을 덮고, 작업 종류·양식은 모으고, 원문은 줄바꿈으로 잇는다")
    void merge() {
        RuleInputExtractService rule = service(new NoLlmExtractClient());
        var first = rule.recorded("탱크 내부 청소 산소 20.9% 황화수소 3ppm", 1L);
        var second = rule.recorded("황화수소 2ppm 일산화탄소 5ppm", 1L);

        var merged = RuleInputExtractService.merge(List.of(first, second));

        assertThat(merged.result().text()).isEqualTo(first.result().text() + "\n" + second.result().text());
        Map<MeasurementItem, BigDecimal> values = new java.util.LinkedHashMap<>();
        merged.result().measurements().forEach(v -> values.put(v.item(), v.value()));
        assertThat(values).containsEntry(MeasurementItem.O2, new BigDecimal("20.9"))
                .containsEntry(MeasurementItem.CO, new BigDecimal("5"));
        assertThat(values.get(MeasurementItem.H2S)).isEqualByComparingTo("2");   // 뒤 문장 값
        assertThat(merged.result().measurements()).extracting(RuleInputExtractor.Value::item)
                .doesNotHaveDuplicates();
        assertThat(merged.result().workTypes()).extracting(RuleInputExtractor.WorkTypeHit::type).contains(WorkType.CONFINED);
        assertThat(merged.extractor()).isEqualTo(first.extractor());               // 같은 추출기면 하나만

        assertThat(RuleInputExtractService.merge(List.of(first))).isSameAs(first); // 한 문장이면 그대로
    }
}
