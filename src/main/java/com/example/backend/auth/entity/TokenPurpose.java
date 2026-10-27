package com.example.backend.auth.entity;

/** 메일 링크 토큰 용도. DB account_tokens.purpose CHECK 와 같은 값. */
public enum TokenPurpose {
    EMAIL_VERIFY,
    EMAIL_CHANGE,
    PASSWORD_RESET
}
