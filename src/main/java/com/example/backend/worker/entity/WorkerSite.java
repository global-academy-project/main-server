package com.example.backend.worker.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 작업자 현장 배정. 테이블 규칙은 V2 worker_sites.
 * active = 지금 이 현장에서 일하는가 (작업 배치 화면 목록). 떠나면 assigned_to 를 넣고 active = false.
 */
@Entity
@Table(name = "worker_sites")
@IdClass(WorkerSiteId.class)
public class WorkerSite {

    @Id
    @Column(name = "worker_id", nullable = false, updatable = false)
    private Long workerId;

    @Id
    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "assigned_from", nullable = false)
    private LocalDate assignedFrom;

    @Column(name = "assigned_to")
    private LocalDate assignedTo;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected WorkerSite() {
    }

    public WorkerSite(Long workerId, Long siteId, LocalDate assignedFrom) {
        if (workerId == null || siteId == null || assignedFrom == null) {
            throw new IllegalArgumentException("작업자·현장·배정일이 필요합니다.");
        }
        this.workerId = workerId;
        this.siteId = siteId;
        this.assignedFrom = assignedFrom;
    }

    /** 현장을 떠남. 떠난 날은 배정일 이후여야 한다 (DB ck_ws_period, ck_ws_active) */
    public void leave(LocalDate date) {
        if (date == null || date.isBefore(assignedFrom)) {
            throw new IllegalArgumentException("떠난 날은 배정일 이후여야 합니다.");
        }
        this.assignedTo = date;
        this.active = false;
    }

    /** 같은 현장에 다시 배정 (행은 하나라 배정일을 새로 잡는다) */
    public void rejoin(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("배정일이 필요합니다.");
        }
        this.assignedFrom = date;
        this.assignedTo = null;
        this.active = true;
    }

    public Long getWorkerId() { return workerId; }
    public Long getSiteId() { return siteId; }
    public boolean isActive() { return active; }
    public LocalDate getAssignedFrom() { return assignedFrom; }
    public LocalDate getAssignedTo() { return assignedTo; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
