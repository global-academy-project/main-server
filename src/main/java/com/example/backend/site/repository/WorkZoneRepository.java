package com.example.backend.site.repository;

import com.example.backend.site.entity.WorkZone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkZoneRepository extends JpaRepository<WorkZone, Long> {

    List<WorkZone> findBySiteIdAndActiveTrueOrderByCodeAsc(Long siteId);

    /** 다른 현장 구역을 못 쓰게 현장까지 같이 조회 */
    Optional<WorkZone> findByIdAndSiteId(Long id, Long siteId);

    boolean existsBySiteIdAndCode(Long siteId, String code);
}
