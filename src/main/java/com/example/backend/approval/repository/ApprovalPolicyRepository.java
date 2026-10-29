package com.example.backend.approval.repository;

import com.example.backend.approval.entity.ApprovalPolicy;
import com.example.backend.approval.entity.ApprovalPolicyId;
import com.example.backend.approval.entity.ApprovalTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApprovalPolicyRepository extends JpaRepository<ApprovalPolicy, ApprovalPolicyId> {

    /** 결재선 (비어 있으면 기본 1단계 APPROVER) */
    List<ApprovalPolicy> findBySiteIdAndTargetTypeOrderByStepNoAsc(Long siteId, ApprovalTarget targetType);

    List<ApprovalPolicy> findBySiteIdOrderByTargetTypeAscStepNoAsc(Long siteId);

    /** 결재선 전체 교체 전에 지운다 */
    void deleteBySiteIdAndTargetType(Long siteId, ApprovalTarget targetType);
}
