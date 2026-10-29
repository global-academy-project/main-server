package com.example.backend.agent.repository;

import com.example.backend.agent.entity.AgentMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {

    List<AgentMessage> findByConversationIdOrderBySeqAsc(Long conversationId);

    /** 요약 이후 메시지만 (LLM 에 넘길 최근 대화) */
    List<AgentMessage> findByConversationIdAndSeqGreaterThanOrderBySeqAsc(Long conversationId, int afterSeq);
}
