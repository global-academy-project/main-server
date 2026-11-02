package com.example.backend.nearmiss.dto;

import com.example.backend.tag.entity.TagAxis;
import com.example.backend.tag.entity.TagSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 태그 검토 저장 (PUT /api/v1/near-misses/{id}/tags). 전체 교체, [] 는 모두 삭제.
 *  - source USER: confidence 는 null
 *  - source AI: 이미 저장된 AI 태그를 그대로 둘 때만 (축·값·확신도가 같아야 함)
 */
public record SaveTagsRequest(@NotNull Integer lockVersion, @NotNull @Size(max = 60) List<@Valid @NotNull Tag> tags) {

    public record Tag(@NotNull TagAxis axis, @NotBlank @Size(max = 30) String value, @NotNull TagSource source,
                      BigDecimal confidence) {
    }
}
