package com.example.backend.risk.dto;

import com.example.backend.risk.entity.AssessmentType;
import com.example.backend.risk.entity.RiskLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;

/**
 * 일일 위험성평가 작성(POST /api/v1/risk-assessments)·수정(PUT /api/v1/risk-assessments/{id}). 웹 명세에 맞춘 모양.
 *  - 작성: siteId·workId 필수, assessmentType 은 DAILY 만 (비우면 DAILY). lockVersion 은 비움
 *  - 수정: siteId·workId·assessmentType 은 무시, lockVersion 필수 (다르면 409 CONFLICT_MODIFIED)
 *  - items: 위험요인 전체 (보낸 순서가 순번, seq 는 무시). 수정은 통째로 바꾼다
 *  - riskLevel: 서버가 빈도×강도로 계산한 등급보다 높게 보내면 그 값, 낮거나 비우면 계산 등급으로 저장 (C-2, 응답 computedLevel)
 *  - accidentType: 발생형태 태그 코드 (예: FIRE, FALL). 모르는 코드는 400
 *  - formData: 양식의 나머지 내용. JSON 객체 또는 JSON 객체 문자열. 비우면 기존 값 그대로
 */
public record SaveRiskAssessmentRequest(
        Long siteId,
        Long workId,
        AssessmentType assessmentType,
        @NotNull LocalDate assessDate,
        JsonNode formData,
        @NotNull @Size(max = 50) List<@Valid @NotNull Item> items,
        Integer lockVersion
) {

    public record Item(
            Integer seq,
            @NotBlank @Size(max = 100) String workStep,
            @NotBlank @Size(max = 300) String hazard,
            @Size(max = 30) String accidentType,
            @Min(1) @Max(5) int frequency,
            @Min(1) @Max(5) int severity,
            RiskLevel riskLevel,
            @NotBlank @Size(max = 2000) String controls,
            @Min(1) @Max(5) Integer residualFrequency,
            @Min(1) @Max(5) Integer residualSeverity
    ) {
    }
}
