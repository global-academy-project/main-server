package com.example.backend.audit.repository;

import com.example.backend.audit.entity.AuditReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AuditReportRepository extends JpaRepository<AuditReport, Long> {

    /** 현장의 보고서 (삭제 제외, 최신순) */
    List<AuditReport> findBySiteIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(Long siteId);

    /** 같은 보고서 (채팅 감사 분기): 같은 현장·작성자·기간·항목 구성, 삭제 제외. 여럿이면 가장 최근 것 */
    Optional<AuditReport> findFirstBySiteIdAndCreatedByAndDateFromAndDateToAndQueryTypesAndDeletedAtIsNullOrderByIdDesc(
            Long siteId, Long createdBy, LocalDate dateFrom, LocalDate dateTo, String queryTypes);
}
