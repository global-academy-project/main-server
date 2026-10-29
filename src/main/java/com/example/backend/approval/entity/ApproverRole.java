package com.example.backend.approval.entity;

/** 결재 단계를 처리할 수 있는 역할(이상). V6 required_role CHECK 와 같은 값. ADMIN 은 회사 관리자. */
public enum ApproverRole {
    APPROVER, SITE_ADMIN, ADMIN
}
