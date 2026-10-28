package com.example.backend.rule.repository;

import com.example.backend.rule.entity.RuleEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleEvaluationRepository extends JpaRepository<RuleEvaluation, Long> {
}
