package com.example.backend.work.entity;

/**
 * 작업 경고의 출처 (DB ck_wwarn_source).
 * NEAR_MISS 같은 구역 아차사고 (near_miss_id 필수) / RULE 규칙 판정 WARN (rule_evaluation_id 필수)
 * ZONE_HAZARD 구역 위험 메모 / QUALIFICATION 배치 인원 자격 만료·부족
 */
public enum WarningSource {
    NEAR_MISS, RULE, ZONE_HAZARD, QUALIFICATION
}
