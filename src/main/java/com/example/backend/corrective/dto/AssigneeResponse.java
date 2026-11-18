package com.example.backend.corrective.dto;

/** 개선조치 담당자 후보: 이 현장 사용 중 인원(현장 역할) + 회사 관리자 */
public record AssigneeResponse(Long userId, String name, String role) {
}
