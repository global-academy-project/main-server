package com.example.backend.inspection.entity;

import java.util.Collection;
import java.util.Objects;

/** 점검표 종합 결과 (DB ck_insp_result). 제출할 때 필수 (ck_insp_done) */
public enum InspectionResult {
    PASS, FAIL, PARTIAL;

    /**
     * 항목 결과로 종합 결과 계산. 해당 없음(NA)은 빼고 본다.
     * 불량 없음 → PASS / 일부 불량 → PARTIAL / 전부 불량 → FAIL. 결과를 안 적은 항목이 있으면 null (아직 제출 불가)
     */
    public static InspectionResult of(Collection<ItemResult> items) {
        if (items.isEmpty() || items.stream().anyMatch(Objects::isNull)) {
            return null;
        }
        long counted = items.stream().filter(r -> r != ItemResult.NA).count();
        long ng = items.stream().filter(r -> r == ItemResult.NG).count();
        if (ng == 0) return PASS;
        return ng == counted ? FAIL : PARTIAL;
    }
}
