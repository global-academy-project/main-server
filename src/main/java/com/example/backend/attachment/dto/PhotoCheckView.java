package com.example.backend.attachment.dto;

import com.example.backend.attachment.entity.PhotoCheckResult;
import com.example.backend.attachment.entity.PhotoCheckType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 사진의 최신 확인 결과 (photo_checks 마지막 행). 확인한 적이 없으면 첨부 응답의 check 가 null.
 *  - result: PASS 통과 / FAIL 맞지 않음 / UNSURE 확인 못 함 (사람이 봐야 함). 알리기만 하고 막지 않는다
 *  - detectedDate: 사진의 촬영 날짜, detectedPeople: 사진 속 인원, helmetMissing: 안전모 미착용 인원 (모르면 null)
 *  - dateConfirmed: 촬영 날짜 정보가 없어 작성자가 TBM 당일 사진으로 확인함
 *  - duplicateOfId: 똑같은 파일인 이전 첨부, messages: 화면에 그대로 보여 줄 안내
 *  - model: 확인한 방법 (RULE:v1 = 파일 정보만, HUMAN = 작성자가 고침), reason: 작성자가 고친 사유
 *  - checkedTbmDate: 확인할 때의 TBM 날짜 (지금 날짜와 다르면 다시 확인 필요)
 */
public record PhotoCheckView(Long checkId, PhotoCheckType checkType, PhotoCheckResult result, LocalDate detectedDate,
                             Integer detectedPeople, Integer helmetMissing, boolean dateConfirmed, Long duplicateOfId,
                             List<String> messages, String model, String reason, LocalDate checkedTbmDate,
                             LocalDateTime checkedAt) {
}
