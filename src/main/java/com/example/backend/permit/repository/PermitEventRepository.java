package com.example.backend.permit.repository;

import com.example.backend.permit.entity.PermitEvent;
import com.example.backend.permit.entity.PermitEventStatus;
import com.example.backend.permit.entity.PermitEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PermitEventRepository extends JpaRepository<PermitEvent, Long> {

    List<PermitEvent> findByPermitIdOrderByCreatedAtAscIdAsc(Long permitId);

    /** 결정 기다리는 요청이 있나 (같은 종류 중복 요청 막기) */
    boolean existsByPermitIdAndEventTypeAndStatus(Long permitId, PermitEventType eventType, PermitEventStatus status);

    /** 승인된 연장 중 가장 늦은 종료 시각 (없으면 비어 있음) → 실제 종료 시각 = max(허가 종료, 이 값) */
    @Query("""
            select max(e.newValidTo) from PermitEvent e
             where e.permitId = :permitId
               and e.eventType = com.example.backend.permit.entity.PermitEventType.EXTEND
               and e.status = com.example.backend.permit.entity.PermitEventStatus.APPROVED
            """)
    Optional<LocalDateTime> findLatestApprovedExtension(@Param("permitId") Long permitId);
}
