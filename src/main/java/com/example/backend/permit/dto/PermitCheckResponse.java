package com.example.backend.permit.dto;

import com.example.backend.permit.entity.PermitStatus;
import com.example.backend.rule.service.RuleCheckResult;

/** POST /api/v1/permits/{id}/check: 판정 결과 + 바뀐 허가 상태 */
public record PermitCheckResponse(Long permitId, PermitStatus status, boolean canSubmit, RuleCheckResult evaluation) {
}
