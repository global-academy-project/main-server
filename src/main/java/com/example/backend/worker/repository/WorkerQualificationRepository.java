package com.example.backend.worker.repository;

import com.example.backend.worker.entity.WorkerQualification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface WorkerQualificationRepository extends JpaRepository<WorkerQualification, Long> {

    List<WorkerQualification> findByWorkerIdOrderByIssuedOnDesc(Long workerId);

    /** 그 날짜에 유효한 자격 (예: 밀폐공간 작업 배치 시 CONFINED_SPACE_SPECIAL 확인) */
    @Query("""
            select q from WorkerQualification q
             where q.workerId = :workerId and q.qualCode = :qualCode
               and q.issuedOn <= :date and (q.expiresOn is null or q.expiresOn >= :date)
             order by q.issuedOn desc
            """)
    List<WorkerQualification> findValid(@Param("workerId") Long workerId, @Param("qualCode") String qualCode,
                                        @Param("date") LocalDate date);
}
