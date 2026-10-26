package com.example.backend.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.List;

/** 공통 응답 봉투. 성공: {success,data} / 실패: {success:false,error:{code,message,details[{field,reason}]}} */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ErrorBody error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    /** data가 없는 성공 응답. {"success":true,"data":null} 로 나간다 (삭제 등). */
    public static ApiResponse<Object> okEmpty() {
        return new ApiResponse<>(true, NullNode.getInstance(), null);
    }

    public static ApiResponse<Void> fail(String code, String message, List<ErrorDetail> details) {
        return new ApiResponse<>(false, null, new ErrorBody(code, message, details));
    }
}