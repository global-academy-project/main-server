package com.example.backend.approval.service;

import com.example.backend.approval.entity.ApprovalTarget;

/**
 * 결재 대상 문서별 처리. 결재가 결정되면 문서 상태를 같은 트랜잭션에서 바꾼다 (계획서 7-5장 문서 상태와 결재 상태 표).
 * 문서 패키지(permit, risk …)가 구현하고, ApprovalService 가 종류별로 찾아 부른다.
 */
public interface ApprovalTargetHandler {

    ApprovalTarget type();

    /** 승인함에 보일 이름 (예: HOT-20261102-001 화기작업허가) */
    String title(Long targetId);

    /** 마지막 단계 승인 전에 확인할 조건. 맞지 않으면 BusinessException(INVALID_STATE) */
    default void checkBeforeFinalApprove(Long targetId) {
    }

    /** 마지막 단계까지 승인됨 */
    void onApproved(Long targetId);

    /** 반려됨 (사유는 결재·이력에 있음) */
    void onRejected(Long targetId);

    /** 요청자가 회수함 → 다시 작성 중 */
    void onCanceled(Long targetId);
}
