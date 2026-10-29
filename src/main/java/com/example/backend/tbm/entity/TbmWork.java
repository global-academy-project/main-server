package com.example.backend.tbm.entity;

import com.example.backend.work.entity.Work;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * TBM에서 다룬 작업. 테이블 규칙은 V4 tbm_works.
 * 같은 현장 작업만 (복합 외래키), 작업 하나는 하루에 TBM 한 번 (uk_tw_work_date).
 * tbm_date 는 TBM 기록 날짜를 그대로 들고 있다 (TBM 날짜가 바뀌면 DB가 같이 바꿈) → 직접 바꾸지 않는다.
 */
@Entity
@Table(name = "tbm_works")
@IdClass(TbmWorkId.class)
public class TbmWork {

    @Id
    @Column(name = "tbm_id", nullable = false, updatable = false)
    private Long tbmId;

    @Id
    @Column(name = "work_id", nullable = false, updatable = false)
    private Long workId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Column(name = "tbm_date", nullable = false, updatable = false)
    private LocalDate tbmDate;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected TbmWork() {
    }

    /** TBM 기록과 작업으로 만든다 (현장·날짜를 TBM 기록에서 가져옴) */
    public static TbmWork of(TbmRecord tbm, Work work) {
        if (!tbm.getSiteId().equals(work.getSiteId())) {
            throw new IllegalArgumentException("다른 현장의 작업은 TBM에 넣을 수 없습니다.");
        }
        TbmWork tw = new TbmWork();
        tw.tbmId = tbm.getId();
        tw.workId = work.getId();
        tw.siteId = tbm.getSiteId();
        tw.tbmDate = tbm.getTbmDate();
        return tw;
    }

    public Long getTbmId() { return tbmId; }
    public Long getWorkId() { return workId; }
    public Long getSiteId() { return siteId; }
    public LocalDate getTbmDate() { return tbmDate; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
