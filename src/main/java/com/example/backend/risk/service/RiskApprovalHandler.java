package com.example.backend.risk.service;

import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.service.ApprovalTargetHandler;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.risk.entity.RiskAssessment;
import com.example.backend.risk.repository.RiskAssessmentRepository;
import com.example.backend.work.repository.WorkRepository;
import org.springframework.stereotype.Component;

/** 위험성평가 결재 처리. 반려되면 REJECTED 로 보존하고, 다시 쓰려면 새 판 (판 만들기는 위험성평가 작성 API 와 함께) */
@Component
public class RiskApprovalHandler implements ApprovalTargetHandler {

    private final RiskAssessmentRepository riskRepository;
    private final WorkRepository workRepository;

    public RiskApprovalHandler(RiskAssessmentRepository riskRepository, WorkRepository workRepository) {
        this.riskRepository = riskRepository;
        this.workRepository = workRepository;
    }

    @Override
    public ApprovalTarget type() {
        return ApprovalTarget.RISK;
    }

    @Override
    public String title(Long riskId) {
        return riskRepository.findById(riskId).map(r -> {
            String work = r.getWorkId() == null ? null
                    : workRepository.findById(r.getWorkId()).map(w -> w.getTitle()).orElse(null);
            String base = r.getTitle() != null ? r.getTitle() : "일일 위험성평가";
            return work == null ? base : base + " · " + work;
        }).orElse("위험성평가 " + riskId);
    }

    @Override
    public void onApproved(Long riskId) {
        load(riskId).approve();
    }

    @Override
    public void onRejected(Long riskId) {
        load(riskId).reject();
    }

    @Override
    public void onCanceled(Long riskId) {
        load(riskId).withdraw();
    }

    private RiskAssessment load(Long riskId) {
        return riskRepository.findById(riskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "위험성평가를 찾을 수 없습니다."));
    }
}
