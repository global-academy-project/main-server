package com.example.backend.common.exception;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    // ErrorCode에 정의된 기본 메시지를 사용하는 생성자
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    // ErrorCode와 별도의 예외 메시지를 지정하는 생성자
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() { return errorCode; }
}
