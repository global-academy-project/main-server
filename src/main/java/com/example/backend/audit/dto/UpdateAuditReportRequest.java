package com.example.backend.audit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 감사보고서 고치기: 제목·종합 의견·항목별 요약(queryType 으로 찾음). 집계·검증 결과는 바꿀 수 없다 */
public record UpdateAuditReportRequest(@NotBlank @Size(max = 200) String title, @Size(max = 20000) String summary,
                                       List<Item> items, @NotNull Integer lockVersion) {

    public record Item(String queryType, @Size(max = 20000) String summary) {
    }
}
