package com.example.backend.rule.service;

/**
 * 규칙 하나의 판정 상태 (checks[]).
 * PASS 기준 충족 / FAIL 값이 있는데 기준을 벗어남 / MISSING 값이 없음 (측정 안 함·입력 안 함)
 * FAIL·MISSING 은 violations 에도 같은 규칙으로 들어간다.
 */
public enum CheckStatus {
    PASS, FAIL, MISSING
}
