package com.example.backend.worker.repository;

import com.example.backend.worker.entity.WorkerSite;
import com.example.backend.worker.entity.WorkerSiteId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkerSiteRepository extends JpaRepository<WorkerSite, WorkerSiteId> {

    List<WorkerSite> findBySiteIdAndActiveTrue(Long siteId);

    List<WorkerSite> findByWorkerId(Long workerId);

    /** 작업 배치 전에 이 현장 작업자인지 확인 */
    boolean existsByWorkerIdAndSiteIdAndActiveTrue(Long workerId, Long siteId);
}
