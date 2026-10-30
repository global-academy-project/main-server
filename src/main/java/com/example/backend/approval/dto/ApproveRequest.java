package com.example.backend.approval.dto;

import jakarta.validation.constraints.Size;

/** POST /api/v1/approvals/{id}/approve — 의견은 선택 */
public record ApproveRequest(@Size(max = 500) String comment) {
}
