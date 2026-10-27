package com.example.backend.user.entity;

/** 계정 등급. DB users.role CHECK 와 같은 값. */
public enum UserRole {
    /** 이 회사의 모든 현장 */
    ADMIN,
    /** site_members 에 있는 현장만 */
    MEMBER
}
