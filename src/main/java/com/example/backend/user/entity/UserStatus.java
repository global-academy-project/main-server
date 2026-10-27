package com.example.backend.user.entity;

/** 계정 상태. DB users.status CHECK 와 같은 값. */
public enum UserStatus {
    /** 가입 직후. 이메일 인증 + 관리자 승인(현장 권한) 전 */
    PENDING,
    /** 사용 가능. DB가 이메일 인증된 계정만 허용한다 (ck_users_active_verified) */
    ACTIVE,
    /** 관리자가 사용 중지 */
    INACTIVE,
    /** 로그인 실패 누적으로 잠김 */
    LOCKED
}
