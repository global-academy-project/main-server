package com.example.backend.extract.dto;

import com.example.backend.rule.entity.WorkType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 문장 → 규칙 입력 추출 (POST /api/v1/extract/rule-input). 저장하지 않는다.
 *  - workType: 화면에서 이미 정한 작업 종류가 있으면. 그 종류로만 미리 판정한다 (비우면 문장에서 찾은 종류 전부)
 */
public record ExtractRequest(@NotNull @Positive Long siteId, @NotBlank @Size(max = 2000) String text, WorkType workType) {
}
