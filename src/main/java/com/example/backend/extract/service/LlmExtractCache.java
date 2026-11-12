package com.example.backend.extract.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * LLM 추출 응답을 잠깐 기억한다 (11/11). 미리 보기에서 부른 LLM 을 허가서 반영 때 다시 부르지 않기 위해서.
 * <ul>
 *   <li>키: LLM 에 보낸 문장 (사전 추출이 정리한 text). 측정 항목·작업 종류 목록은 늘 같아 키에 넣지 않는다</li>
 *   <li>값: 서버가 ai-model 에서 직접 받은 응답만 둔다 (화면이 보낸 값을 믿지 않는 원칙 그대로). 확인·구역 대조는 매번 새로 한다</li>
 *   <li>실패(빈 응답)도 기억한다: 반영은 사용자가 마지막으로 본 결과와 같게 기록하려고 실패도 그대로 쓰고,
 *       미리 보기는 retryFailure=true 로 실패만 다시 부른다 (LLM 이 느린 날 재시도가 막히지 않게)</li>
 *   <li>app.extract.llm.cache-minutes 동안, 최대 MAX_ENTRIES 건. 넘치면 가장 오래 안 쓴 것부터 지운다</li>
 *   <li>서버 한 대의 메모리라 재시작·여러 대 실행이면 빗나가고, 그때는 지금처럼 다시 부른다 (틀린 결과는 안 나옴)</li>
 * </ul>
 */
@Component
public class LlmExtractCache {

    static final int MAX_ENTRIES = 200;

    private record Entry(Optional<LlmExtraction> response, Instant at) {
    }

    private final Clock clock;
    private final Duration ttl;
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public LlmExtractCache(Clock clock, @Value("${app.extract.llm.cache-minutes:30}") long minutes) {
        this.clock = clock;
        this.ttl = Duration.ofMinutes(minutes);
    }

    /**
     * 기억한 응답이 있으면 쓰고, 없거나 지났으면 call 로 부른 뒤 기억한다.
     *
     * @param retryFailure true 면 기억한 응답이 실패(빈 값)일 때 다시 부른다 (미리 보기). false 면 실패도 그대로 쓴다 (반영)
     */
    public Optional<LlmExtraction> get(String text, boolean retryFailure, Supplier<Optional<LlmExtraction>> call) {
        Instant now = clock.instant();
        Entry hit;
        synchronized (entries) {
            hit = entries.get(text);
        }
        if (hit != null && hit.at().plus(ttl).isAfter(now) && (hit.response().isPresent() || !retryFailure)) {
            return hit.response();
        }
        Optional<LlmExtraction> response = call.get();   // LLM 호출은 잠금 밖에서 (최대 수십 초)
        synchronized (entries) {
            entries.put(text, new Entry(response, clock.instant()));
        }
        return response;
    }

    /**
     * 기억한 응답만 본다 (LLM 을 부르지 않음, 11/12 허가서 반영용).
     * 비어 있으면 기억이 없음(30분 지남·서버 재시작), 안의 Optional 이 비어 있으면 기억한 응답이 실패.
     */
    public Optional<Optional<LlmExtraction>> peek(String text) {
        Instant now = clock.instant();
        synchronized (entries) {
            Entry hit = entries.get(text);
            return hit != null && hit.at().plus(ttl).isAfter(now) ? Optional.of(hit.response()) : Optional.empty();
        }
    }

    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }
}
