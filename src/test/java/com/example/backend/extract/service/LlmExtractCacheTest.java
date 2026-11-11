package com.example.backend.extract.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** LLM 응답 기억 (미리 보기 → 허가서 반영에서 LLM 을 한 번만 부르기) */
class LlmExtractCacheTest {

    /** 시간을 손으로 넘기는 시계 */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-11T03:00:00Z");

        @Override public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void plus(Duration d) { now = now.plus(d); }
    }

    static final LlmExtraction OK = new LlmExtraction(List.of(), List.of(), "김도윤", "감시인 김도윤");

    final TestClock clock = new TestClock();
    final LlmExtractCache cache = new LlmExtractCache(clock, 30);
    final AtomicInteger calls = new AtomicInteger();

    Supplier<Optional<LlmExtraction>> answer(Optional<LlmExtraction> response) {
        return () -> {
            calls.incrementAndGet();
            return response;
        };
    }

    @Test
    @DisplayName("같은 문장이면 LLM 을 한 번만 부른다 (미리 보기 → 반영)")
    void sameTextCallsOnce() {
        assertThat(cache.get("산소 20.9%", true, answer(Optional.of(OK)))).contains(OK);
        assertThat(cache.get("산소 20.9%", false, answer(Optional.of(OK)))).contains(OK);
        assertThat(calls).hasValue(1);

        cache.get("산소 18%", true, answer(Optional.of(OK)));   // 문장이 다르면 새로 부른다
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("기억 시간(30분)이 지나면 다시 부른다")
    void expires() {
        cache.get("산소 20.9%", true, answer(Optional.of(OK)));
        clock.plus(Duration.ofMinutes(29));
        cache.get("산소 20.9%", false, answer(Optional.of(OK)));
        assertThat(calls).hasValue(1);

        clock.plus(Duration.ofMinutes(2));
        cache.get("산소 20.9%", false, answer(Optional.of(OK)));
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("실패: 반영은 실패를 그대로 쓰고(본 것과 같게), 미리 보기는 다시 부른다")
    void failure() {
        assertThat(cache.get("산소 20.9%", true, answer(Optional.empty()))).isEmpty();

        assertThat(cache.get("산소 20.9%", false, answer(Optional.of(OK)))).isEmpty();   // 반영: 다시 부르지 않음
        assertThat(calls).hasValue(1);

        assertThat(cache.get("산소 20.9%", true, answer(Optional.of(OK)))).contains(OK);  // 미리 보기: 다시 부름
        assertThat(calls).hasValue(2);
        assertThat(cache.get("산소 20.9%", false, answer(Optional.empty()))).contains(OK); // 이제 성공을 기억
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("200건을 넘으면 가장 오래 안 쓴 것부터 지운다")
    void bounded() {
        for (int i = 0; i < LlmExtractCache.MAX_ENTRIES; i++) {
            cache.get("문장 " + i, true, answer(Optional.of(OK)));
        }
        cache.get("문장 0", false, answer(Optional.of(OK)));   // 0 을 최근에 씀
        cache.get("새 문장", true, answer(Optional.of(OK)));    // 넘침 → 1 이 지워짐
        assertThat(cache.size()).isEqualTo(LlmExtractCache.MAX_ENTRIES);

        int before = calls.get();
        cache.get("문장 0", false, answer(Optional.of(OK)));
        assertThat(calls).hasValue(before);
        cache.get("문장 1", false, answer(Optional.of(OK)));
        assertThat(calls).hasValue(before + 1);
    }
}
