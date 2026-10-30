package com.example.backend.record.dto;

import java.util.List;
import java.util.Map;

/**
 * search_records 응답 (D 확정 문서 5-4).
 *  - conditions: 실제로 쓴 조건 (siteId 포함). 화면은 "조회 조건"으로 보여 준다
 *  - total: 전체 건수, truncated: records 가 최대 건수(yaml)에서 잘렸으면 true
 *  - summary: 통계 유형은 집계 값, 목록 유형은 count 만
 *  - evidence: 답변 근거로 저장할 기록 {type, id}
 */
public record SearchRecordsResult(String queryType, Map<String, Object> conditions, int total, boolean truncated,
                                  Map<String, Object> summary, List<Map<String, Object>> records,
                                  List<Evidence> evidence) {

    public record Evidence(String type, Long id) {
    }
}
