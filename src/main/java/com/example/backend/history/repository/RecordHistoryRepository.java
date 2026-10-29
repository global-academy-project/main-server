package com.example.backend.history.repository;

import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/** 추가·조회만 쓴다 (수정·삭제는 DB 트리거가 막음) */
public interface RecordHistoryRepository extends JpaRepository<RecordHistory, Long> {

    /** 한 기록의 이력 (시간 순) */
    List<RecordHistory> findByTargetTypeAndTargetIdOrderByCreatedAtAscIdAsc(HistoryTarget targetType, Long targetId);

    /** 현장 이력 (기간, 최근 순) */
    Page<RecordHistory> findBySiteIdAndCreatedAtBetweenOrderByCreatedAtDescIdDesc(Long siteId, LocalDateTime from,
                                                                                  LocalDateTime to, Pageable pageable);
}
