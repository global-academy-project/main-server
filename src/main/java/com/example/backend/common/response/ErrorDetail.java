package com.example.backend.common.response;

/** 검증 실패 시 필드별 사유 */
public record ErrorDetail(String field, String reason) {
}