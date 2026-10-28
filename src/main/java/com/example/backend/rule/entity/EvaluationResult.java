package com.example.backend.rule.entity;

/** 판정 결과 (DB ck_eval_result). 위반 중 가장 무거운 것으로 정한다 */
public enum EvaluationResult {
    PASS,
    WARN,
    BLOCK
}
