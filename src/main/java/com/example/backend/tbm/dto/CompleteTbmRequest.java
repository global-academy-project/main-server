package com.example.backend.tbm.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 실시 완료. heldAt 은 TBM 날짜여야 한다. attendeeCount 는 실제 참석 인원 (등록 작업자 수와 달라도 됨, 비우면 등록 참석자 수).
 * lockVersion 필수 (조회 응답 값, 다르면 409 CONFLICT_MODIFIED)
 */
public record CompleteTbmRequest(@NotNull Integer lockVersion, @NotNull LocalDateTime heldAt,
                                 @Min(0) @Max(500) Integer attendeeCount) {
}
