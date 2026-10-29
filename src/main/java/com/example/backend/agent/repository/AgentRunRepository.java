package com.example.backend.agent.repository;

import com.example.backend.agent.entity.AgentRun;
import com.example.backend.agent.entity.AgentRunStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {

    List<AgentRun> findByConversationIdOrderByIdAsc(Long conversationId);

    /** 멈춘 실행 정리용: 이 시각 전에 시작해 아직 PROCESSING 인 실행 */
    List<AgentRun> findByStatusAndCreatedAtBefore(AgentRunStatus status, LocalDateTime before);

    /**
     * 결과·실패 저장 전에 실행 행을 잠근다 (SELECT ... FOR UPDATE).
     * AI 응답 저장과 멈춘 실행 정리가 동시에 와도 한쪽만 저장되게 (다른 쪽은 PROCESSING 이 아니어서 건너뜀).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AgentRun r where r.id = :id")
    Optional<AgentRun> findForUpdate(@Param("id") Long id);
}
