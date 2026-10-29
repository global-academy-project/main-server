package com.example.backend.corrective.entity;

/**
 * 개선 조치의 출처 (DB ck_ca_source). OTHER 외에는 source_id 필수.
 * NEAR_MISS 아차사고 / INSPECTION 점검 불량 / RULE_VIOLATION 규칙 위반 / AUDIT 감사 지적 / OTHER 기타
 */
public enum ActionSource {
    NEAR_MISS, INSPECTION, RULE_VIOLATION, AUDIT, OTHER
}
