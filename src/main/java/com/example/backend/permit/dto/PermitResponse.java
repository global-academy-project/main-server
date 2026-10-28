package com.example.backend.permit.dto;

import com.example.backend.permit.entity.MeasurementItem;
import com.example.backend.permit.entity.PermitMeasurement;
import com.example.backend.permit.entity.PermitStatus;
import com.example.backend.permit.entity.PermitType;
import com.example.backend.rule.entity.EvaluationResult;
import com.example.backend.rule.entity.RuleSeverity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 허가서 상세.
 *  - measurements: 작업 전 측정값 전부 (재측정 포함, 측정 시각 순)
 *  - lastEvaluation: 최근 판정 (없으면 null → 판정해야 승인 요청 가능)
 *  - canSubmit: 지금 승인 요청 가능한지 (작성 중 + 최근 판정이 있고 BLOCK 이 아님)
 */
public record PermitResponse(
        Long permitId, Long siteId, Long workId, PermitType permitType, int revision, String permitNo,
        LocalDateTime validFrom, LocalDateTime validTo, PermitStatus status,
        String watcherName, String supervisorName, Map<String, Object> form,
        List<Measurement> measurements, Evaluation lastEvaluation, boolean canSubmit, int lockVersion
) {

    public record Measurement(Long id, MeasurementItem item, BigDecimal value, String unit, LocalDateTime measuredAt,
                              String location, String measurerName, boolean remeasure) {
        public static Measurement from(PermitMeasurement m) {
            return new Measurement(m.getId(), m.getItem(), m.getValue(), m.getUnit(), m.getMeasuredAt(),
                    m.getLocation(), m.getMeasurerName(), m.isRemeasure());
        }
    }

    public record Evaluation(Long evaluationId, EvaluationResult result, LocalDateTime evaluatedAt,
                             List<Violation> violations) {
    }

    public record Violation(String ruleCode, String actual, String expected, RuleSeverity severity,
                            String message, String legalBasis) {
    }
}
