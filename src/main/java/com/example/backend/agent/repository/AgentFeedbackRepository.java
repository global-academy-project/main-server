package com.example.backend.agent.repository;

import com.example.backend.agent.entity.AgentFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentFeedbackRepository extends JpaRepository<AgentFeedback, Long> {

    Optional<AgentFeedback> findByRunIdAndUserId(Long runId, Long userId);
}
