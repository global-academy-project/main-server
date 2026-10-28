package com.example.backend.rule.service;

import com.example.backend.rule.entity.EvaluationTarget;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/**
 * 판정 대상 문서와 에이전트 실행이 그 현장 것인지 확인한다.
 * 허가·작업·점검 엔티티가 아직 없어서 COUNT 쿼리로 본다 (테이블 이름은 enum 에 고정, 사용자 입력이 들어가지 않음).
 */
@Component
public class RuleTargetLookup {

    private final EntityManager entityManager;

    public RuleTargetLookup(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public boolean targetExists(EvaluationTarget type, Long targetId, Long siteId) {
        return count("SELECT COUNT(*) FROM " + type.table() + " WHERE id = ?1 AND site_id = ?2", targetId, siteId) > 0;
    }

    public boolean agentRunExists(Long agentRunId, Long siteId) {
        return count("SELECT COUNT(*) FROM agent_runs WHERE id = ?1 AND site_id = ?2", agentRunId, siteId) > 0;
    }

    private long count(String sql, Long id, Long siteId) {
        Object n = entityManager.createNativeQuery(sql)
                .setParameter(1, id)
                .setParameter(2, siteId)
                .getSingleResult();
        return ((Number) n).longValue();
    }
}
