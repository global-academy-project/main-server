package com.example.backend.risk.repository;

import com.example.backend.risk.entity.RiskAssessmentItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RiskAssessmentItemRepository extends JpaRepository<RiskAssessmentItem, Long> {

    List<RiskAssessmentItem> findByAssessmentIdOrderBySeqAsc(Long assessmentId);

    int countByAssessmentId(Long assessmentId);
}
