package com.example.backend.approval.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * POST /api/v1/approvals/{id}/reject — 보완 요청도 반려로 통일 (계획서 7-5장).
 * missingItems: 화면에서 고른 누락 항목 (예: "measurement.LEL", "form.extinguisher_ready"). 반려 이력에 남는다
 */
public record RejectRequest(@NotBlank @Size(max = 500) String reason, @Size(max = 30) List<@Size(max = 100) String> missingItems) {
}
