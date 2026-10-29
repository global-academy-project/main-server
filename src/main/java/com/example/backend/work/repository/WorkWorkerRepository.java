package com.example.backend.work.repository;

import com.example.backend.work.entity.WorkRole;
import com.example.backend.work.entity.WorkWorker;
import com.example.backend.work.entity.WorkWorkerId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkWorkerRepository extends JpaRepository<WorkWorker, WorkWorkerId> {

    List<WorkWorker> findByWorkId(Long workId);

    List<WorkWorker> findByWorkerId(Long workerId);

    /** 화재감시자·감시인 배치 여부 */
    boolean existsByWorkIdAndRoleInWork(Long workId, WorkRole roleInWork);
}
