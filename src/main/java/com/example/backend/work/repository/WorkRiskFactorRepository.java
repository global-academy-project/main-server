package com.example.backend.work.repository;

import com.example.backend.work.entity.WorkRiskFactor;
import com.example.backend.work.entity.WorkRiskFactorId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkRiskFactorRepository extends JpaRepository<WorkRiskFactor, WorkRiskFactorId> {

    List<WorkRiskFactor> findByWorkId(Long workId);
}
