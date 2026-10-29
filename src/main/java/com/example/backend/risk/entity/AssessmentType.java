package com.example.backend.risk.entity;

/**
 * 위험성평가 종류 (DB ck_risk_type, 위험성평가 지침).
 * DAILY 당일 작업 단위 (작업 필수) / INITIAL 최초 / REGULAR 정기 / ADHOC 수시 (설비·공정 변경, 사고 후)
 * DAILY 외에는 공정·구역 단위라 작업 없이 존재하고 이름(title)이 필수.
 */
public enum AssessmentType {
    DAILY, INITIAL, REGULAR, ADHOC
}
