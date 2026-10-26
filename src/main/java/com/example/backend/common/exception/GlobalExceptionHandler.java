package com.example.backend.common.exception;

import com.example.backend.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import com.example.backend.common.response.ErrorDetail;

import java.util.List;

/* 전역 예외를 공통 API 응답 형식으로 변환한다. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        return build(e.getErrorCode(), e.getMessage(), List.of());
    }

    /** @Valid 검증 실패 (MethodArgumentNotValidException 포함) */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBind(BindException e) {
        List<ErrorDetail> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ErrorDetail(f.getField(), f.getDefaultMessage()))
                .toList();
        return build(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.getDefaultMessage(), details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception e) {
        return build(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.getDefaultMessage(), List.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleTooLarge(MaxUploadSizeExceededException e) {
        return build(ErrorCode.FILE_TOO_LARGE, ErrorCode.FILE_TOO_LARGE.getDefaultMessage(), List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethod(HttpRequestMethodNotSupportedException e) {
        return build(ErrorCode.INVALID_REQUEST, e.getMessage(), List.of());
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(Exception e) {
        return ResponseEntity.status(404)
                .body(ApiResponse.fail("NOT_FOUND", "요청한 경로를 찾을 수 없습니다.", List.of()));
    }

    /* 예상하지 못한 예외는 서버 로그에 기록하고 내부 정보는 응답에 노출하지 않는다.*/
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
        log.error("Unhandled exception", e);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage(), List.of());
    }
    /* 에러 코드에 정의된 HTTP 상태와 공통 응답 형식을 적용한다.*/
    private ResponseEntity<ApiResponse<Void>> build(ErrorCode code, String message, List<ErrorDetail> details) {
        return ResponseEntity.status(code.getStatus())
                .body(ApiResponse.fail(code.name(), message, details));
    }
}
