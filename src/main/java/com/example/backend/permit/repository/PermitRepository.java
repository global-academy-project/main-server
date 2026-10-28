package com.example.backend.permit.repository;

import com.example.backend.permit.entity.Permit;
import com.example.backend.permit.entity.PermitType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermitRepository extends JpaRepository<Permit, Long> {

    boolean existsByWorkIdAndPermitTypeAndRevision(Long workId, PermitType permitType, int revision);

    /** 허가번호 일련번호용: 같은 현장에서 이 접두어(예: HOT-20261028-)로 시작하는 허가 수 */
    long countBySiteIdAndPermitNoStartingWith(Long siteId, String prefix);
}
