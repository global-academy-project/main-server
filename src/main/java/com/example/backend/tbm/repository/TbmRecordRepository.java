package com.example.backend.tbm.repository;

import com.example.backend.tbm.entity.TbmRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface TbmRecordRepository extends JpaRepository<TbmRecord, Long> {

    List<TbmRecord> findBySiteIdAndTbmDateOrderByIdAsc(Long siteId, LocalDate tbmDate);

    /** TBM 마감 알림: 그날 기록이 있는가 */
    boolean existsBySiteIdAndTbmDate(Long siteId, LocalDate tbmDate);
}
