package com.example.backend.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    UNSUPPORTED_FILE_TYPE(HttpStatus.BAD_REQUEST, "PDF 파일만 업로드할 수 있습니다."),
    ENGINE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "현재 사용할 수 없는 엔진입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),

    // 계정 상태 (비밀번호가 맞았을 때만 알려준다)
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "이메일 인증이 필요합니다."),
    ACCOUNT_PENDING(HttpStatus.FORBIDDEN, "관리자 승인을 기다리는 중입니다."),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "로그인 실패가 반복되어 계정이 잠겼습니다."),
    ACCOUNT_INACTIVE(HttpStatus.FORBIDDEN, "사용이 중지된 계정입니다. 관리자에게 문의하세요."),
    PASSWORD_CHANGE_REQUIRED(HttpStatus.FORBIDDEN, "비밀번호를 변경해야 합니다."),

    // 메일 링크 토큰
    TOKEN_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 링크입니다."),
    TOKEN_ALREADY_USED(HttpStatus.CONFLICT, "이미 사용한 링크입니다."),
    TOKEN_EXPIRED_LINK(HttpStatus.GONE, "링크가 만료되었습니다. 새 링크를 받아 주세요."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),

    PASSWORD_REUSED(HttpStatus.BAD_REQUEST, "최근에 사용한 비밀번호는 다시 쓸 수 없습니다."),
    CONFLICT_MODIFIED(HttpStatus.CONFLICT, "다른 곳에서 먼저 수정했습니다. 새로고침 후 다시 시도해 주세요."),
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "현재 비밀번호가 올바르지 않습니다."),
    INVALID_STATE(HttpStatus.CONFLICT, "현재 상태에서는 할 수 없는 작업입니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    SITE_NOT_FOUND(HttpStatus.NOT_FOUND, "현장을 찾을 수 없습니다."),
    SITE_NOT_ALLOWED(HttpStatus.FORBIDDEN, "권한이 없는 현장입니다."),

    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "문서를 찾을 수 없습니다."),
    RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "처리 이력을 찾을 수 없습니다."),
    IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "이미지를 찾을 수 없습니다."),
    FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "저장된 파일을 찾을 수 없습니다."),
    EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    ALREADY_PROCESSING(HttpStatus.CONFLICT, "처리 중인 문서입니다."),
    FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "파일 크기는 20MB 이하여야 합니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
    OCR_SERVER_ERROR(HttpStatus.BAD_GATEWAY, "OCR 서버 호출에 실패했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() { return status; }

    public String getDefaultMessage() { return defaultMessage; }
}
