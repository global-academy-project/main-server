package com.example.backend.audit.repository;

import com.example.backend.audit.entity.AuditReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditReportRepository extends JpaRepository<AuditReport, Long> {

    /** 현장의 보고서 (삭제 제외, 최신순) */
    List<AuditReport> findBySiteIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(Long siteId);
}
