package com.example.backend.job.repository;

import com.example.backend.job.entity.AsyncJob;
import com.example.backend.job.entity.JobStatus;
import com.example.backend.job.entity.JobTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AsyncJobRepository extends JpaRepository<AsyncJob, Long> {

    /** [PDF 출력] 두 번 눌러도 작업은 하나 */
    Optional<AsyncJob> findByIdempotencyKey(String idempotencyKey);

    List<AsyncJob> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(JobTarget targetType, Long targetId);

    /**
     * 처리할 작업 하나 가져가기. 여러 서버가 동시에 불러도 같은 행을 가져가지 않는다 (SKIP LOCKED).
     * 트랜잭션 안에서 부르고, 가져간 행에 start() 후 커밋한다.
     */
    @Query(value = """
            SELECT * FROM async_jobs
             WHERE status = 'QUEUED' AND run_after <= :now
             ORDER BY id
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<AsyncJob> claimNext(@Param("now") LocalDateTime now);

    /** 서버가 죽어 RUNNING 에 멈춘 작업 찾기 (started_at 이 오래된 것) */
    List<AsyncJob> findByStatusAndStartedAtBefore(JobStatus status, LocalDateTime before);
}
