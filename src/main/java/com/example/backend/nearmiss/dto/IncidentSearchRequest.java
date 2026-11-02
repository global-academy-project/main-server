package com.example.backend.nearmiss.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 관련 사고 검색 (POST /api/v1/incident-search). 선택 현장 안에서만.
 *  - filters: 빈 배열·null 은 그 축 조건 없음. 축 안은 OR, 축끼리는 AND
 *  - query: 제목·내용 글자 포함 (대소문자 무시)
 *  - limit: 기본 yaml app.near-miss.search.default-limit, 최대 max-limit
 */
public record IncidentSearchRequest(@NotNull @Positive Long siteId, @Positive Long zoneId, @Size(max = 500) String query,
                                    Filters filters, @Min(1) @Max(100) Integer limit) {

    public record Filters(List<String> severity, List<String> accidentType, List<String> hazard, List<String> workType,
                          List<String> cas) {
    }
}
