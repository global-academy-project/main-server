package com.example.backend.tbm.repository;

import com.example.backend.tbm.entity.TbmWork;
import com.example.backend.tbm.entity.TbmWorkId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface TbmWorkRepository extends JpaRepository<TbmWork, TbmWorkId> {

    List<TbmWork> findByTbmId(Long tbmId);

    /** 이 작업이 그날 TBM에 이미 들어갔는가 (DB uk_tw_work_date) */
    boolean existsByWorkIdAndTbmDate(Long workId, LocalDate tbmDate);

    /** 작업에 연결된 TBM (최근 것 먼저) */
    List<TbmWork> findByWorkIdOrderByTbmDateDesc(Long workId);
}
