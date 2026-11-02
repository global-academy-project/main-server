package com.example.backend.risk.service;

import com.example.backend.risk.entity.RiskLevel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 빈도×강도 점수 → 등급 (yaml app.risk.level). 사람이 작성하는 위험성평가에 쓴다.
 * 에이전트 초안 저장(AgentDraftWriter)도 같은 yaml 값을 쓴다.
 */
@Component
public class RiskLevelPolicy {

    private final int highMinScore;
    private final int mediumMinScore;

    public RiskLevelPolicy(@Value("${app.risk.level.high-min-score}") int highMinScore,
                           @Value("${app.risk.level.medium-min-score}") int mediumMinScore) {
        if (mediumMinScore < 1 || highMinScore <= mediumMinScore) {
            throw new IllegalStateException("app.risk.level: 1 ≤ medium-min-score < high-min-score 여야 합니다.");
        }
        this.highMinScore = highMinScore;
        this.mediumMinScore = mediumMinScore;
    }

    /**
     * 저장할 등급 (C-2): 사람이 계산 등급보다 높게 고르면 그 값, 낮거나 비우면 계산 등급.
     * 위험을 낮게 보는 실수는 막고, 보수적인 판단(올리기)은 남긴다.
     */
    public RiskLevel resolve(int score, RiskLevel requested) {
        RiskLevel computed = levelOf(score);
        return requested != null && rank(requested) > rank(computed) ? requested : computed;
    }

    private static int rank(RiskLevel level) {
        return switch (level) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
        };
    }

    public RiskLevel levelOf(int score) {
        if (score >= highMinScore) return RiskLevel.HIGH;
        if (score >= mediumMinScore) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }
}
