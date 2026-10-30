package com.example.backend.work.repository;

import com.example.backend.work.entity.Work;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface WorkRepository extends JpaRepository<Work, Long> {

    /** 현장의 기간 작업 (날짜·시작 시각 순) */
    List<Work> findBySiteIdAndWorkDateBetweenOrderByWorkDateAscStartAtAscIdAsc(Long siteId, LocalDate from, LocalDate to,
                                                                           Pageable pageable);

    /** 대시보드: 현장 기간 작업 전체 (작업일 기준) */
    List<Work> findBySiteIdAndWorkDateBetweenOrderByWorkDateAscIdAsc(Long siteId, LocalDate from, LocalDate to);
}
