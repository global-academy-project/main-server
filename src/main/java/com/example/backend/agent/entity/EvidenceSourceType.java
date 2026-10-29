package com.example.backend.agent.entity;

/**
 * 근거 출처 종류 (DB ck_ev_source).
 *  검색 문서(ACCIDENT_REPORT·SIF·KOSHA_GUIDE·RISK_GUIDE·CHECKLIST)는 kb_point_id(어느 청크인지)와 snippet 필수
 *  RULE 은 rule_id 필수
 */
public enum EvidenceSourceType {
    ACCIDENT_REPORT, SIF, KOSHA_GUIDE, RISK_GUIDE, CHECKLIST, LAW, MSDS, NEAR_MISS, RULE, RECORD;

    public boolean requiresKbChunk() {
        return this == ACCIDENT_REPORT || this == SIF || this == KOSHA_GUIDE || this == RISK_GUIDE || this == CHECKLIST;
    }
}
