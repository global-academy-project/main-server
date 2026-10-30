package com.example.backend.risk.repository;

import com.example.backend.risk.entity.AssessmentType;
import com.example.backend.risk.entity.RiskAssessment;
import com.example.backend.risk.entity.RiskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, Long> {

    /** 작업의 일일 평가 최신 판 */
    Optional<RiskAssessment> findTopByWorkIdOrderByRevisionDesc(Long workId);

    List<RiskAssessment> findByWorkIdOrderByRevisionDesc(Long workId);

    /** 작업 목록: 여러 작업의 위험성평가 (모든 판) */
    List<RiskAssessment> findByWorkIdIn(Collection<Long> workIds);

    /** 일일 평가가 참고할 구역의 정기·수시 평가 (예: 승인된 것, 최근 순) */
    List<RiskAssessment> findBySiteIdAndZoneIdAndAssessmentTypeInAndStatusOrderByAssessDateDesc(
            Long siteId, Long zoneId, Collection<AssessmentType> types, RiskStatus status);
}
