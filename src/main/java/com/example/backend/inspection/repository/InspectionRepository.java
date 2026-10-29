package com.example.backend.inspection.repository;

import com.example.backend.inspection.entity.Inspection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface InspectionRepository extends JpaRepository<Inspection, Long> {

    /** 그날 현장 점검표 (대시보드 미완료 항목) */
    List<Inspection> findBySiteIdAndInspectDateOrderByIdAsc(Long siteId, LocalDate inspectDate);

    /** 작업에서 만든 점검표 */
    List<Inspection> findByWorkIdOrderByIdAsc(Long workId);

    /** 장비의 가장 최근 점검 */
    Optional<Inspection> findTopByEquipmentIdOrderByInspectDateDescIdDesc(Long equipmentId);

    Optional<Inspection> findByIdAndSiteId(Long id, Long siteId);
}
