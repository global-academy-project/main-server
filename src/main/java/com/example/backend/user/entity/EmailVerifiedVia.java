package com.example.backend.user.entity;

/** 이메일 소유 확인 방법. DB users.email_verified_via CHECK 와 같은 값. */
public enum EmailVerifiedVia {
    /** 인증 메일 링크 클릭 */
    LINK,
    /** 메일이 막힌 환경에서 관리자가 대면 확인 */
    ADMIN
}
