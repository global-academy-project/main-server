package com.example.backend.risk.entity;

/** 평가 방법 (DB ck_risk_method). FREQ_SEVERITY 빈도·강도 / CHECKLIST 체크리스트 / THREE_STEP 3단계 판단 */
public enum RiskMethod {
    FREQ_SEVERITY, CHECKLIST, THREE_STEP
}
