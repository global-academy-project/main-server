package com.example.backend.tbm.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * TBM 참석자. 테이블 규칙은 V4 tbm_attendees.
 * 그 현장에 배정된 작업자만 (worker_sites 복합 외래키). 승인된 TBM에는 추가·삭제 불가 (V9 trg_ta_lock_*).
 */
@Entity
@Table(name = "tbm_attendees")
@IdClass(TbmAttendeeId.class)
public class TbmAttendee {

    @Id
    @Column(name = "tbm_id", nullable = false, updatable = false)
    private Long tbmId;

    @Id
    @Column(name = "worker_id", nullable = false, updatable = false)
    private Long workerId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    /** 작업 전 건강 상태 확인 (null = 확인 안 함) */
    @Column(name = "health_ok")
    private Boolean healthOk;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected TbmAttendee() {
    }

    /** TBM 기록의 현장으로 만든다 */
    public static TbmAttendee of(TbmRecord tbm, Long workerId, Boolean healthOk) {
        if (workerId == null) {
            throw new IllegalArgumentException("작업자가 필요합니다.");
        }
        TbmAttendee a = new TbmAttendee();
        a.tbmId = tbm.getId();
        a.workerId = workerId;
        a.siteId = tbm.getSiteId();
        a.healthOk = healthOk;
        return a;
    }

    public void checkHealth(Boolean healthOk) { this.healthOk = healthOk; }

    public Long getTbmId() { return tbmId; }
    public Long getWorkerId() { return workerId; }
    public Long getSiteId() { return siteId; }
    public Boolean getHealthOk() { return healthOk; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
