package com.example.backend.rule.entity;

/** 위반했을 때 무게. BLOCK 은 승인 요청을 막고, WARN 은 알리기만 한다 */
public enum RuleSeverity {
    BLOCK,
    WARN
}
