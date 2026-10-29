package com.example.backend.work.entity;

import java.io.Serializable;
import java.util.Objects;

/** work_workers 복합키 (work_id, worker_id) */
public class WorkWorkerId implements Serializable {

    private Long workId;
    private Long workerId;

    protected WorkWorkerId() {
    }

    public WorkWorkerId(Long workId, Long workerId) {
        this.workId = workId;
        this.workerId = workerId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkWorkerId that)) return false;
        return Objects.equals(workId, that.workId) && Objects.equals(workerId, that.workerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workId, workerId);
    }
}
