package com.example.backend.site.entity;

/** 현장별 역할. DB site_members.role CHECK 와 같은 값. */
public enum SiteRole {
    /** 작업·허가·평가 작성, 제출 */
    WRITER,
    /** 제출된 문서 승인·반려 */
    APPROVER,
    /** 그 현장의 설정·인원 관리 */
    SITE_ADMIN
}
