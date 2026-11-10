package com.example.backend.attachment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * TBM 사진 확인 결과 고치기 (작성자가 사진을 보고). 비운 칸은 "모름".
 *  - people: 사진 속 인원, helmetMissing: 안전모 미착용 인원 (0 이면 모두 착용). 안전화는 보지 않음
 *  - dateConfirmed: 촬영 날짜 정보가 없는 사진을 TBM 당일 사진으로 확인 (날짜 정보가 있는 사진에는 쓸 수 없음)
 *  - reason: 고친 사유 (필수, 200자)
 */
public record CorrectPhotoCheckRequest(@Min(0) @Max(500) Integer people,
                                       @Min(0) @Max(500) Integer helmetMissing,
                                       Boolean dateConfirmed,
                                       @NotBlank @Size(max = 200) String reason) {
}
