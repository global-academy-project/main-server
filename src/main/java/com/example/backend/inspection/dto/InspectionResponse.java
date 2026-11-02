package com.example.backend.inspection.dto;

import com.example.backend.approval.entity.ApprovalStatus;
import com.example.backend.inspection.entity.InspectionResult;
import com.example.backend.inspection.entity.InspectionStatus;
import com.example.backend.inspection.entity.InspectionTarget;
import com.example.backend.inspection.entity.ItemResult;

import java.time.LocalDate;
import java.util.List;

/**
 * 점검표 조회.
 *  - result: 제출 때 확정된 종합 결과. expectedResult: 지금 항목 결과로 계산한 값 (다 적지 않았으면 null)
 *    (NA 제외하고 불량 없음 PASS / 일부 불량 PARTIAL / 전부 불량 FAIL)
 *  - answered/total: 결과를 적은 항목 수 / 전체, canSubmit: 작성 중이고 모든 항목에 결과가 있음
 *  - approvalId·approvalStatus: 가장 최근 결재 (없으면 null)
 *  - items[].section·detail: 양식의 구역 제목·설명 (양식에서 빠진 항목이면 null)
 */
public record InspectionResponse(Long inspectionId, Long siteId, InspectionTarget targetType, Long zoneId, String zoneName,
                                 Long equipmentId, String equipmentName, Long workId, LocalDate inspectDate,
                                 String templateCode, String templateName, InspectionResult result,
                                 InspectionResult expectedResult, InspectionStatus status, Long inspectorId,
                                 String inspectorName, int answered, int total, boolean canSubmit, int lockVersion,
                                 Long approvalId, ApprovalStatus approvalStatus, List<Item> items) {

    public record Item(int seq, String itemCode, String section, String question, String detail, ItemResult result,
                       String note) {
    }
}
