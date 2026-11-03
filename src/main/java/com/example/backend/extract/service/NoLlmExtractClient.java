package com.example.backend.extract.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** LLM 을 쓰지 않음 (기본값, ai-model 없이 개발할 때). 규칙 추출 결과만 쓴다 */
@Component
@ConditionalOnProperty(name = "app.extract.llm.client", havingValue = "none", matchIfMissing = true)
public class NoLlmExtractClient implements LlmExtractClient {

    @Override
    public Optional<LlmExtraction> extract(String text, List<String> items, List<String> workTypes) {
        return Optional.empty();
    }

    @Override
    public String name() {
        return null;
    }
}
