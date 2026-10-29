package com.example.backend.agent.repository;

import com.example.backend.agent.entity.EvidenceCitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvidenceCitationRepository extends JpaRepository<EvidenceCitation, Long> {

    List<EvidenceCitation> findByRunIdOrderByIdAsc(Long runId);

    /** 업무 기록(예: RISK 3)의 근거 목록 */
    List<EvidenceCitation> findByTargetTypeAndTargetIdOrderByIdAsc(String targetType, Long targetId);
}
