-- refresh_tokens.revoke_reason 에 PASSWORD_CHANGED 추가
-- 비밀번호를 재설정·변경하면 그 사용자의 로그인 세션(refresh token)을 모두 끊는다.
-- 기존 값(LOGOUT, ROTATED, LOCKED, ADMIN, REUSE_DETECTED)으로는 사유를 구분할 수 없어서 추가한다.
-- 작성 C · 2026-10-27 (인증 2단계)

ALTER TABLE refresh_tokens
    DROP CHECK ck_rt_reason;

ALTER TABLE refresh_tokens
    ADD CONSTRAINT ck_rt_reason CHECK (revoke_reason IS NULL OR revoke_reason IN
        ('LOGOUT', 'ROTATED', 'LOCKED', 'ADMIN', 'REUSE_DETECTED', 'PASSWORD_CHANGED'));
