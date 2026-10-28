package com.example.backend.rule.repository;

import com.example.backend.rule.entity.RuleViolation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RuleViolationRepository extends JpaRepository<RuleViolation, Long> {

    List<RuleViolation> findByEvaluationIdOrderByIdAsc(Long evaluationId);
}
