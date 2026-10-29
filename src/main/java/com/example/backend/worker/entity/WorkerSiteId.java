package com.example.backend.worker.entity;

import java.io.Serializable;
import java.util.Objects;

/** worker_sites 복합키 (worker_id, site_id) */
public class WorkerSiteId implements Serializable {

    private Long workerId;
    private Long siteId;

    protected WorkerSiteId() {
    }

    public WorkerSiteId(Long workerId, Long siteId) {
        this.workerId = workerId;
        this.siteId = siteId;
    }

    public Long getWorkerId() { return workerId; }
    public Long getSiteId() { return siteId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkerSiteId that)) return false;
        return Objects.equals(workerId, that.workerId) && Objects.equals(siteId, that.siteId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workerId, siteId);
    }
}
