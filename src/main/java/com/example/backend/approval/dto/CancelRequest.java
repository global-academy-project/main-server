package com.example.backend.approval.dto;

import jakarta.validation.constraints.Size;

/** POST /api/v1/approvals/{id}/cancel — 요청자 회수, 메모는 선택 */
public record CancelRequest(@Size(max = 500) String comment) {
}
