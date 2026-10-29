package com.example.backend.agent.repository;

import com.example.backend.agent.entity.AgentConversation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentConversationRepository extends JpaRepository<AgentConversation, Long> {

    /** 메시지 추가 전에 대화 행을 잠근다 (SELECT ... FOR UPDATE) → nextSeq() 순번이 꼬이지 않게 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AgentConversation c where c.id = :id")
    Optional<AgentConversation> findForUpdate(@Param("id") Long id);

    /** 최근 항목: 고정 먼저, 그다음 최근 메시지 순 (지운 대화 제외) */
    List<AgentConversation> findByUserIdAndDeletedAtIsNullOrderByPinnedDescLastMessageAtDesc(Long userId);
}
