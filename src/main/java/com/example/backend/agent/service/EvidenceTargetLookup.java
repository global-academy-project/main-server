package com.example.backend.agent.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 기록 질문 답변의 근거 기록이 실제로 있고 그 현장 것인지 확인한다 (AI 가 만든 id 를 그대로 믿지 않음).
 * 테이블 이름은 아래 표에 고정 (사용자·AI 입력이 SQL 에 들어가지 않음). 표에 없는 종류는 저장하지 않는다.
 */
@Component
public class EvidenceTargetLookup {

    private static final Map<String, String> TABLES = Map.of(
            "WORK", "works",
            "PERMIT", "permits",
            "RISK", "risk_assessments",
            "TBM", "tbm_records",
            "INSPECTION", "inspections",
            "APPROVAL", "approvals",
            "CORRECTIVE_ACTION", "corrective_actions",
            "NEAR_MISS", "near_misses",
            "DAILY_LOG", "daily_safety_logs");

    private final EntityManager entityManager;

    public EvidenceTargetLookup(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public boolean exists(String type, Long id, Long siteId) {
        String table = type == null ? null : TABLES.get(type);
        if (table == null || id == null) {
            return false;
        }
        Object n = entityManager.createNativeQuery("SELECT COUNT(*) FROM " + table + " WHERE id = ?1 AND site_id = ?2")
                .setParameter(1, id).setParameter(2, siteId).getSingleResult();
        return ((Number) n).longValue() > 0;
    }
}
