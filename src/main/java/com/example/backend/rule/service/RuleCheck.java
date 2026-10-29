package com.example.backend.rule.service;

import com.example.backend.rule.entity.Rule;

/**
 * 적용한 규칙 하나의 결과 (통과 포함). 허가서 측정값 칸처럼 통과한 항목도 보여 줘야 할 때 쓴다.
 * actual 은 입력값 (없으면 null). message·action 은 FAIL·MISSING 일 때만 있다 (violations 와 같은 문구).
 */
public record RuleCheck(Rule rule, CheckStatus status, String actual, String expected, String message, String action) {
}
