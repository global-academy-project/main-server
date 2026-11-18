package com.example.backend.history.repository;

import com.example.backend.history.entity.RecordAccessLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import com.example.backend.history.entity.AccessResource;

import java.time.LocalDateTime;

/** 추가·조회만 쓴다 (수정·삭제는 DB 트리거가 막음) */
public interface RecordAccessLogRepository extends JpaRepository<RecordAccessLog, Long> {

    Page<RecordAccessLog> findBySiteIdOrderByCreatedAtDescIdDesc(Long siteId, Pageable pageable);

    Page<RecordAccessLog> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    /** 감사보고서 요약 중 다시 조회: 이 사람이 since 이후 이 기록을 이미 열었는지 */
    boolean existsByUserIdAndResourceTypeAndResourceIdAndCreatedAtGreaterThanEqual(Long userId, AccessResource resourceType,
                                                                                  Long resourceId, LocalDateTime since);
}
