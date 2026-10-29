package com.example.backend.agent.repository;

import com.example.backend.agent.entity.AgentStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentStepRepository extends JpaRepository<AgentStep, Long> {

    List<AgentStep> findByRunIdOrderBySeqAsc(Long runId);
}
