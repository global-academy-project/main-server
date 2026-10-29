package com.example.backend.nearmiss.repository;

import com.example.backend.nearmiss.entity.NearMiss;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NearMissRepository extends JpaRepository<NearMiss, Long> {

    Page<NearMiss> findBySiteIdOrderByOccurredAtDesc(Long siteId, Pageable pageable);

    /** 다른 현장 기록을 못 보게 현장까지 같이 조회 */
    Optional<NearMiss> findByIdAndSiteId(Long id, Long siteId);

    /** 같은 구역 최근 아차사고 (시나리오 C 경고). from = 오늘 - sites.near_miss_warn_days */
    List<NearMiss> findBySiteIdAndZoneIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
            Long siteId, Long zoneId, LocalDateTime from);
}
