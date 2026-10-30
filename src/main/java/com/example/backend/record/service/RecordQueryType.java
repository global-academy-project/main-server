package com.example.backend.record.service;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * 기록 조회 유형 11개 (D 확정 문서 5-2). AI 는 SQL 을 만들지 않고 이 중 하나와 조건만 고른다.
 * 모든 유형은 from·to 가 필수다. 기간 기준 날짜는 유형마다 다르다 (아래 주석).
 */
public enum RecordQueryType {
    /** 측정값 없이 승인된 허가. 기간 = 허가 시작일 */
    MISSING_MEASUREMENTS("missing_measurements", Set.of("permitType")),
    /** 허가 요청·승인·반려·차단 통계. 기간 = 허가 시작일 */
    PERMIT_APPROVAL_STATISTICS("permit_approval_statistics", Set.of("permitType")),
    /** 대기 중 결재. 기간 = 요청일 */
    PENDING_APPROVALS("pending_approvals", Set.of("targetType", "stepNo")),
    /** 반려 후 다시 쓴 문서 (위험성평가·작업허가). 기간 = 반려일 */
    REJECTED_REVISIONS("rejected_revisions", Set.of("targetType")),
    /** 개선조치 목록 + 조치율. 기간 = 등록일 */
    OPEN_CORRECTIVE_ACTIONS("open_corrective_actions", Set.of("status")),
    /** 불량(NG) 항목이 있는 점검. 기간 = 점검일 */
    FAILED_INSPECTIONS("failed_inspections", Set.of("zoneId", "templateCode")),
    /** TBM 없는 작업. 기간 = 작업일 */
    MISSING_TBM("missing_tbm", Set.of("workType", "zoneId")),
    /** 위험성평가 실시율. 기간 = 작업일 */
    RISK_ASSESSMENT_RATE("risk_assessment_rate", Set.of("workType")),
    /** 결재자별 처리 통계 (단계별 결정자 기준). 기간 = 결정일 */
    APPROVER_STATISTICS("approver_statistics", Set.of("approverId")),
    /** 기록 변경 이력. 기간 = 변경 시각 */
    RECORD_CHANGE_HISTORY("record_change_history", Set.of("recordType", "recordId", "userId")),
    /** 규칙 판정으로 차단된 허가 (공식 판정만). 기간 = 판정 시각 */
    BLOCKED_RECORDS("blocked_records", Set.of("permitType"));

    public static final Set<String> COMMON = Set.of("from", "to");

    private final String code;
    private final Set<String> params;

    RecordQueryType(String code, Set<String> params) {
        this.code = code;
        this.params = params;
    }

    public String code() {
        return code;
    }

    /** from·to 말고 받는 조건 */
    public Set<String> params() {
        return params;
    }

    public static Optional<RecordQueryType> of(String code) {
        return Arrays.stream(values()).filter(t -> t.code.equals(code)).findFirst();
    }
}
