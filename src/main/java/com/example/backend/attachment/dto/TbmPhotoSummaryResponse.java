package com.example.backend.attachment.dto;

import com.example.backend.attachment.entity.PhotoCheckResult;

/**
 * TBM 사진 인원 요약 (GET /tbm/{tbmId}/photos/summary). 사진 중 가장 많이 잡힌 인원을 참석자 명부 인원과 비교한다.
 *  - maxPeople: 사진 속 인원 중 가장 큰 값 (모르면 null), rosterCount: 참석자 명부 인원, tolerance: 적어도 통과하는 차이 (yaml)
 *  - result: PASS / UNSURE (알림만), 비교하지 않았으면 null. message: 화면에 그대로 보여 줄 안내
 */
public record TbmPhotoSummaryResponse(int photoCount, Integer maxPeople, int rosterCount, int tolerance,
                                      PhotoCheckResult result, String message) {
}