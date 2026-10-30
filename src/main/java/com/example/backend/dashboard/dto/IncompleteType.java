package com.example.backend.dashboard.dto;

/**
 * 대시보드 미완료 항목 종류 (계획서 7-5장, D 확정 문서 4-2).
 * 마감 전에 아직 끝나지 않은 것. 화면은 이 순서대로 항상 9개를 받는다 (0건 포함).
 */
public enum IncompleteType {
    /** 화기·밀폐 허가가 작성 중인데 작업 전 측정값이 빠짐 (허가 종류별 측정 규칙 항목) */
    MEASUREMENT_MISSING("가스 측정값 미입력"),
    /** 작업이 있는 날, 그 작업과 연결된 TBM 없음. tbm_deadline 이후 지연 */
    TBM_MISSING("TBM 미실시"),
    /** 작업이 있는 날 안전일지 없음. daily_log_deadline 이후 지연 */
    DAILY_LOG_MISSING("안전일지 미작성"),
    /** 점검표 작성 중 */
    INSPECTION_DRAFT("점검 미제출"),
    /** 점검표 승인 대기 */
    INSPECTION_PENDING("점검 승인 대기"),
    /** 개선조치 OPEN·IN_PROGRESS. 기한 지나면 지연 (날짜와 무관, 지금 기준) */
    CORRECTIVE_OPEN("개선조치 미완료"),
    /** 결재 마감 지난 대기 결재 (날짜와 무관, 지금 기준) */
    APPROVAL_OVERDUE("승인 지연"),
    /** 실시 완료된 TBM 의 참석자 서명이 참석자 수보다 적음 */
    SIGNATURE_MISSING("서명 미완료"),
    /** 실시 완료된 TBM 에 사진 없음 */
    PHOTO_MISSING("사진 미첨부");

    private final String label;

    IncompleteType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 날짜(작업일)로 세는 항목. 아니면 지금 기준 현황 */
    public boolean byDate() {
        return this != CORRECTIVE_OPEN && this != APPROVAL_OVERDUE;
    }
}
