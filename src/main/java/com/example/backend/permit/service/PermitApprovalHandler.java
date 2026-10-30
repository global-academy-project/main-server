package com.example.backend.permit.service;

import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.service.ApprovalTargetHandler;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.permit.entity.Permit;
import com.example.backend.permit.repository.PermitRepository;
import com.example.backend.risk.entity.RiskStatus;
import com.example.backend.risk.repository.RiskAssessmentRepository;
import org.springframework.stereotype.Component;

/**
 * 작업허가서 결재 처리.
 * 최종 승인 조건: 그 작업의 위험성평가가 있으면 승인 완료여야 한다 (계획서 7-5장, 3장 순서 위험성평가 → 허가).
 * 위험성평가가 아예 없는 작업(직접 등록한 작업)은 지금은 통과시킨다. 위험성평가 작성 API 가 생기면 필수로 바꾼다.
 */
@Component
public class PermitApprovalHandler implements ApprovalTargetHandler {

    private final PermitRepository permitRepository;
    private final RiskAssessmentRepository riskRepository;

    public PermitApprovalHandler(PermitRepository permitRepository, RiskAssessmentRepository riskRepository) {
        this.permitRepository = permitRepository;
        this.riskRepository = riskRepository;
    }

    @Override
    public ApprovalTarget type() {
        return ApprovalTarget.PERMIT;
    }

    @Override
    public String title(Long permitId) {
        return permitRepository.findById(permitId)
                .map(p -> p.getPermitNo() + " " + label(p))
                .orElse("작업허가서 " + permitId);
    }

    @Override
    public void checkBeforeFinalApprove(Long permitId) {
        Permit permit = load(permitId);
        riskRepository.findTopByWorkIdOrderByRevisionDesc(permit.getWorkId()).ifPresent(risk -> {
            if (risk.getStatus() != RiskStatus.APPROVED) {
                throw new BusinessException(ErrorCode.INVALID_STATE,
                        "이 작업의 위험성평가가 승인된 뒤에 작업허가를 승인할 수 있습니다 (현재 위험성평가 " + risk.getStatus() + ").");
            }
        });
    }

    @Override
    public void onApproved(Long permitId) {
        load(permitId).approve();
    }

    @Override
    public void onRejected(Long permitId) {
        load(permitId).reject();
    }

    @Override
    public void onCanceled(Long permitId) {
        load(permitId).withdraw();
    }

    private Permit load(Long permitId) {
        return permitRepository.findById(permitId).orElseThrow(() -> new BusinessException(ErrorCode.PERMIT_NOT_FOUND));
    }

    private static String label(Permit p) {
        return switch (p.getPermitType()) {
            case HOT -> "화기작업허가";
            case CONFINED -> "밀폐공간작업허가";
            case HEIGHT -> "고소작업허가";
        };
    }
}
