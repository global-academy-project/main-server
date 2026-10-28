package com.example.backend.rule.entity;

/** 판정 대상 문서 종류 (DB ck_eval_target). 대상이 그 현장 문서인지 확인할 때 쓰는 테이블 이름을 함께 둔다 */
public enum EvaluationTarget {
    PERMIT("permits"),
    WORK("works"),
    INSPECTION("inspections"),
    RISK("risk_assessments"),
    TBM("tbm_records");

    private final String table;

    EvaluationTarget(String table) {
        this.table = table;
    }

    public String table() { return table; }
}
