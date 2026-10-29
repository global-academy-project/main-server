package com.example.backend.approval.repository;

import com.example.backend.approval.entity.ApprovalStep;
import com.example.backend.approval.entity.ApprovalStepStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApprovalStepRepository extends JpaRepository<ApprovalStep, Long> {

    List<ApprovalStep> findByApprovalIdOrderByStepNoAsc(Long approvalId);

    Optional<ApprovalStep> findByApprovalIdAndStepNo(Long approvalId, int stepNo);

    /** 지정 결재함: 나에게 지정된 지금 차례 단계 */
    List<ApprovalStep> findByAssigneeIdAndStatusOrderByIdAsc(Long assigneeId, ApprovalStepStatus status);
}
