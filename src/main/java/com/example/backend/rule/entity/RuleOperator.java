package com.example.backend.rule.entity;

/** 판정 방법 (DB ck_rules_operator). 기준값은 rules.min_value / max_value / allowed */
public enum RuleOperator {
    /** min 이상(또는 초과) max 미만(또는 이하). 포함 여부는 min_inclusive / max_inclusive */
    RANGE,
    /** min 이상 */
    GTE,
    /** min 초과 */
    GT,
    /** max 이하 */
    LTE,
    /** max 미만 */
    LT,
    /** min 과 같음 */
    EQ,
    /** allowed 목록 중 하나 */
    IN,
    /** 값이 있어야 함 */
    REQUIRED
}
