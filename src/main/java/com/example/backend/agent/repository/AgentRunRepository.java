package com.example.backend.agent.repository;

import com.example.backend.agent.entity.AgentRun;
import com.example.backend.agent.entity.AgentRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {

    List<AgentRun> findByConversationIdOrderByIdAsc(Long conversationId);

    /** 멈춘 실행 정리용: 이 시각 전에 시작해 아직 PROCESSING 인 실행 */
    List<AgentRun> findByStatusAndCreatedAtBefore(AgentRunStatus status, LocalDateTime before);
}
