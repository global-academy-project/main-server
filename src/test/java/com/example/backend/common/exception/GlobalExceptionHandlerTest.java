package com.example.backend.common.exception;

import com.example.backend.common.response.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler: 예외 → 공통 응답 형식")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("BusinessException 은 ErrorCode 의 HTTP 상태와 코드·메시지로 변환")
    void business() {
        ResponseEntity<ApiResponse<Void>> res =
                handler.handleBusiness(new BusinessException(ErrorCode.ALREADY_PROCESSING));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().success()).isFalse();
        assertThat(res.getBody().error().code()).isEqualTo("ALREADY_PROCESSING");
        assertThat(res.getBody().error().message()).isEqualTo("처리 중인 문서입니다.");
        assertThat(res.getBody().error().details()).isEmpty();
    }

    @Test
    @DisplayName("직접 지정한 메시지가 있으면 그 메시지를 사용")
    void business_customMessage() {
        ResponseEntity<ApiResponse<Void>> res =
                handler.handleBusiness(new BusinessException(ErrorCode.INVALID_REQUEST, "page 가 잘못되었습니다."));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().error().message()).isEqualTo("page 가 잘못되었습니다.");
    }

    @Test
    @DisplayName("입력 검증 실패는 400 INVALID_REQUEST + 필드별 사유(details)")
    void bind() {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "signupRequest");
        binding.addError(new FieldError("signupRequest", "password", "8자 이상이어야 합니다."));
        binding.addError(new FieldError("signupRequest", "email", "올바른 형식이 아닙니다."));

        ResponseEntity<ApiResponse<Void>> res = handler.handleBind(new BindException(binding));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().error().code()).isEqualTo("INVALID_REQUEST");
        assertThat(res.getBody().error().details()).extracting("field").containsExactlyInAnyOrder("password", "email");
    }

    @Test
    @DisplayName("본문을 읽을 수 없는 요청(잘못된 JSON 등)은 400 INVALID_REQUEST")
    void unreadableBody() {
        ResponseEntity<ApiResponse<Void>> res =
                handler.handleBadRequest(new IllegalArgumentException("bad json"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().error().code()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    @DisplayName("multipart 용량 초과는 413 FILE_TOO_LARGE")
    void tooLarge() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleTooLarge(new MaxUploadSizeExceededException(20L * 1024 * 1024));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(res.getBody().error().code()).isEqualTo("FILE_TOO_LARGE");
    }

    @Test
    @DisplayName("예상 못 한 예외는 500 이며 내부 예외 메시지를 응답에 노출하지 않는다")
    void unknown_doesNotLeakDetails() {
        ResponseEntity<ApiResponse<Void>> res =
                handler.handleUnknown(new IllegalStateException("jdbc:mysql://secret-host/ocrdb password=abc"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(res.getBody().error().message()).doesNotContain("secret-host").doesNotContain("password");
    }
}
