package com.example.backend.work.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 작업 배치 인원. 테이블 규칙은 V3 work_workers.
 * site_id 를 같이 들고 있어 DB가 "작업과 같은 현장에 배정된 작업자(worker_sites)"만 받는다 (복합 외래키).
 * 화재감시자(FIRE_WATCHER)·감시인(ATTENDANT) 배치 여부는 허가 판정 입력으로 쓸 수 있다.
 */
@Entity
@Table(name = "work_workers")
@IdClass(WorkWorkerId.class)
public class WorkWorker {

    @Id
    @Column(name = "work_id", nullable = false, updatable = false)
    private Long workId;

    @Id
    @Column(name = "worker_id", nullable = false, updatable = false)
    private Long workerId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role_in_work", nullable = false, length = 20)
    private WorkRole roleInWork = WorkRole.WORKER;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected WorkWorker() {
    }

    public WorkWorker(Long workId, Long workerId, Long siteId, WorkRole roleInWork) {
        if (workId == null || workerId == null || siteId == null) {
            throw new IllegalArgumentException("작업·작업자·현장이 필요합니다.");
        }
        this.workId = workId;
        this.workerId = workerId;
        this.siteId = siteId;
        this.roleInWork = roleInWork == null ? WorkRole.WORKER : roleInWork;
    }

    /** 작업의 현장으로 만든다 (현장 불일치 방지) */
    public static WorkWorker of(Work work, Long workerId, WorkRole role) {
        return new WorkWorker(work.getId(), workerId, work.getSiteId(), role);
    }

    public void changeRole(WorkRole role) {
        this.roleInWork = role == null ? WorkRole.WORKER : role;
    }

    public Long getWorkId() { return workId; }
    public Long getWorkerId() { return workerId; }
    public Long getSiteId() { return siteId; }
    public WorkRole getRoleInWork() { return roleInWork; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
