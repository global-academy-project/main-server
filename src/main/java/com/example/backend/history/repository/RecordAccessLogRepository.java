package com.example.backend.history.repository;

import com.example.backend.history.entity.RecordAccessLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** 추가·조회만 쓴다 (수정·삭제는 DB 트리거가 막음) */
public interface RecordAccessLogRepository extends JpaRepository<RecordAccessLog, Long> {

    Page<RecordAccessLog> findBySiteIdOrderByCreatedAtDescIdDesc(Long siteId, Pageable pageable);

    Page<RecordAccessLog> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);
}
