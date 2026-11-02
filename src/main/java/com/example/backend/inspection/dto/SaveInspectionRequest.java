package com.example.backend.inspection.dto;

import com.example.backend.inspection.entity.ItemResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 점검 결과 저장 (PUT /api/v1/inspections/{id}). 작성 중일 때만.
 *  - items: 보낸 항목만 바꾼다 (나머지는 그대로). result 를 null 로 보내면 그 항목 결과를 지운다. NG 는 note 필수
 *  - inspectDate: 비우면 그대로
 *  - lockVersion: 필수. 저장할 때마다 오른다
 */
public record SaveInspectionRequest(@NotNull Integer lockVersion, LocalDate inspectDate,
                                    @NotNull @Size(max = 100) List<@Valid @NotNull Answer> items) {

    public record Answer(@NotBlank @Size(max = 50) String itemCode, ItemResult result, @Size(max = 300) String note) {
    }
}
