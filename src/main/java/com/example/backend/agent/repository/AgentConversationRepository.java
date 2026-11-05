package com.example.backend.agent.repository;

import com.example.backend.agent.entity.AgentConversation;
import com.example.backend.agent.entity.AgentTaskType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
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

    /**
     * 내 지난 대화 (한 현장 · 한 종류): 고정 먼저, 그다음 최근 메시지 순 (지운 대화 제외).
     * 대화 종류는 대화 행에 없고 실행(agent_runs.task_type)에 있다. 한 대화의 실행은 모두 같은 종류(AgentRunService.openTurn).
     */
    @Query("""
            select c from AgentConversation c
            where c.userId = :userId and c.siteId = :siteId and c.deletedAt is null
              and exists (select r.id from AgentRun r where r.conversationId = c.id and r.taskType = :taskType)
            order by c.pinned desc, c.lastMessageAt desc, c.id desc
            """)
    List<AgentConversation> findRecent(@Param("userId") Long userId, @Param("siteId") Long siteId,
                                       @Param("taskType") AgentTaskType taskType, Pageable pageable);
}
