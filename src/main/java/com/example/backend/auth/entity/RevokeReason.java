package com.example.backend.auth.entity;

/** refresh token 폐기 사유. DB refresh_tokens.revoke_reason CHECK 와 같은 값. */
public enum RevokeReason {
    /** 사용자가 로그아웃 */
    LOGOUT,
    /** 재발급으로 새 토큰에 자리를 넘김 (replaced_by 필수) */
    ROTATED,
    /** 계정 잠금 */
    LOCKED,
    /** 관리자 조치 (사용 중지, 임시 비밀번호 등) */
    ADMIN,
    /** 이미 회전된 토큰이 다시 들어옴 → 탈취로 보고 전부 폐기 */
    REUSE_DETECTED,
    /** 비밀번호 재설정·변경 */
    PASSWORD_CHANGED
}
