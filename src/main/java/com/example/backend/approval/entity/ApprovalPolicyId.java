package com.example.backend.approval.entity;

import java.io.Serializable;
import java.util.Objects;

/** approval_policies 복합키 (site_id, target_type, step_no) */
public class ApprovalPolicyId implements Serializable {

    private Long siteId;
    private ApprovalTarget targetType;
    private int stepNo;

    protected ApprovalPolicyId() {
    }

    public ApprovalPolicyId(Long siteId, ApprovalTarget targetType, int stepNo) {
        this.siteId = siteId;
        this.targetType = targetType;
        this.stepNo = stepNo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ApprovalPolicyId that)) return false;
        return stepNo == that.stepNo && Objects.equals(siteId, that.siteId) && targetType == that.targetType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(siteId, targetType, stepNo);
    }
}
