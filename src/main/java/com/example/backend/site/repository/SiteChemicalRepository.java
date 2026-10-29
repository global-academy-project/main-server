package com.example.backend.site.repository;

import com.example.backend.site.entity.SiteChemical;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SiteChemicalRepository extends JpaRepository<SiteChemical, Long> {

    List<SiteChemical> findBySiteIdAndActiveTrueOrderByNameAsc(Long siteId);

    /** 작업 구역에서 취급하는 물질 + 현장 전체(zone 없음) 물질. MSDS 조회·위험성평가 근거용 */
    @Query("""
            select c from SiteChemical c
             where c.siteId = :siteId and c.active = true
               and (c.zoneId = :zoneId or c.zoneId is null)
             order by c.name asc
            """)
    List<SiteChemical> findForZone(@Param("siteId") Long siteId, @Param("zoneId") Long zoneId);
}
