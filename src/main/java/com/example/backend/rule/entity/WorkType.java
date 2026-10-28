package com.example.backend.rule.entity;

/** 판정을 요청할 때의 작업 종류. 규칙의 work_type = ALL 은 어떤 종류든 함께 적용된다 */
public enum WorkType {
    GENERAL,
    HOT,
    CONFINED,
    HEIGHT
}
