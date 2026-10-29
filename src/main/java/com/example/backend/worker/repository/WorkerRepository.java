package com.example.backend.worker.repository;

import com.example.backend.worker.entity.Worker;
import com.example.backend.worker.entity.WorkerStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkerRepository extends JpaRepository<Worker, Long> {

    Optional<Worker> findByUserId(Long userId);

    List<Worker> findByTeamIdAndStatusOrderByNameAsc(Long teamId, WorkerStatus status);

    /** 지금 이 현장에 배정된 활성 작업자 (작업 배치·TBM 참석 화면 목록) */
    @Query("""
            select w from Worker w, WorkerSite ws
             where ws.workerId = w.id and ws.siteId = :siteId and ws.active = true
               and w.status = com.example.backend.worker.entity.WorkerStatus.ACTIVE
             order by w.name asc
            """)
    List<Worker> findActiveBySite(@Param("siteId") Long siteId);
}
