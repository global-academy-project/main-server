package com.example.backend.common.response;

import com.example.backend.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;

/**
 * 필터·보안 단계에서 공통 오류 봉투를 쓴다. 이 단계 예외는 @RestControllerAdvice 가 받지 못하므로 직접 쓴다.
 * 문자열을 이어 붙이지 않고 ApiResponse 를 직렬화해 컨트롤러 오류 응답과 모양·이스케이프가 같다.
 */
public final class ErrorResponseWriter {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ErrorResponseWriter() {
    }

    public static void write(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(MAPPER.writeValueAsString(
                ApiResponse.fail(code.name(), code.getDefaultMessage(), List.of())));
    }
}
