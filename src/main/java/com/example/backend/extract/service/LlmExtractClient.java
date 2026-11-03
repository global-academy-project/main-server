package com.example.backend.extract.service;

import java.util.List;
import java.util.Optional;

/**
 * LLM 추출 보강 (계획서 7-4장 2단계). yaml app.extract.llm.client: none(기본, 규칙 추출만) | fastapi(ai-model).
 * ai-model 은 로컬 GGUF 모델을 쓰므로 폐쇄망(CLOSED)에서도 fastapi 로 켤 수 있다.
 * 실패하면 비어 있는 값을 돌려주고 예외를 던지지 않는다 (규칙 추출 결과만으로 계속).
 */
public interface LlmExtractClient {

    /** items: 받을 측정 항목(O2 등), workTypes: 받을 작업 종류(HOT 등) */
    Optional<LlmExtraction> extract(String text, List<String> items, List<String> workTypes);

    /** 응답의 extractor 표시용 (none 이면 null) */
    String name();
}
