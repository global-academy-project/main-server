package com.example.backend.approval.entity;

/** 결재 대상 기록 종류. V6 approvals·approval_policies.target_type CHECK 와 같은 값. */
public enum ApprovalTarget {
    WORK, RISK, PERMIT, PERMIT_EVENT, INSPECTION, TBM, DAILY_LOG, CORRECTIVE_ACTION
}
