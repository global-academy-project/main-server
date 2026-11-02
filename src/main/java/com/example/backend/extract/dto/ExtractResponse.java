package com.example.backend.extract.dto;

import com.example.backend.extract.service.RuleInputExtractor;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.rule.service.RuleCheckResult;

import java.util.List;
import java.util.Map;

/**
 * 추출 결과 (사용자 확인 전 값).
 *  - extraction: 찾은 작업 종류·구역·측정값·감시자와 근거 문구, notes(비운 이유·확인할 점)
 *  - input: 규칙 입력 모양 {"measurement": {...}, "form": {...}} (확인 후 허가서 측정값·양식에 그대로 넣을 값)
 *  - previews: 작업 종류별 미리 판정 (저장하지 않음, evaluationId 는 항상 null)
 *  - extractor: RULE (규칙 기반) + 사전 판 번호
 */
public record ExtractResponse(RuleInputExtractor.Result extraction, Map<String, Object> input, List<Preview> previews,
                              String extractor) {

    public record Preview(WorkType workType, RuleCheckResult check) {
    }
}
