package com.example.backend.permit.service;

import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.service.ApprovalTargetHandler;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.permit.entity.Permit;
import com.example.backend.permit.repository.PermitRepository;
import com.example.backend.risk.entity.RiskAssessment;
import com.example.backend.risk.entity.RiskStatus;
import com.example.backend.risk.repository.RiskAssessmentRepository;
import org.springframework.stereotype.Component;

/**
 * 작업허가서 결재 처리.
 * 최종 승인 조건: 그 작업의 위험성평가(최신 판)가 반드시 있고 승인 완료여야 한다 (계획서 7-5장, 3장 순서 위험성평가 → 허가).
 * 위험성평가가 없거나 아직 승인되지 않았으면 최종 승인을 막는다 (INVALID_STATE).
 * 반려되면 허가서는 REJECTED 로 남고, 다시 내려면 새 판(POST /permits/{id}/revise)을 만든다.
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
        // 작업허가(화기·밀폐·고소)는 고위험 작업이라 위험성평가가 반드시 있어야 하고, 최신 판이 승인돼 있어야 한다
        RiskAssessment risk = riskRepository.findTopByWorkIdOrderByRevisionDesc(permit.getWorkId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STATE,
                        "이 작업의 위험성평가가 없습니다. 위험성평가를 작성해 승인받은 뒤에 작업허가를 승인할 수 있습니다."));
        if (risk.getStatus() != RiskStatus.APPROVED) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "이 작업의 위험성평가가 승인된 뒤에 작업허가를 승인할 수 있습니다 (현재 위험성평가 " + risk.getStatus() + ").");
        }
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
