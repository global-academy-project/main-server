package com.example.backend.inspection.service;

import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.service.ApprovalTargetHandler;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.inspection.entity.Inspection;
import com.example.backend.inspection.repository.InspectionRepository;
import org.springframework.stereotype.Component;

/**
 * 점검표 결재 (계획서 7-5장). 승인되면 APPROVED(이후 수정 불가),
 * 반려되면 같은 기록을 작성 중으로 되돌려 고쳐서 다시 제출, 회수하면 작성 중.
 */
@Component
public class InspectionApprovalHandler implements ApprovalTargetHandler {

    private final InspectionRepository inspectionRepository;
    private final InspectionTemplates templates;

    public InspectionApprovalHandler(InspectionRepository inspectionRepository, InspectionTemplates templates) {
        this.inspectionRepository = inspectionRepository;
        this.templates = templates;
    }

    @Override
    public ApprovalTarget type() {
        return ApprovalTarget.INSPECTION;
    }

    @Override
    public String title(Long inspectionId) {
        return inspectionRepository.findById(inspectionId).map(i -> i.getInspectDate() + " "
                + templates.find(i.getTemplateCode()).map(InspectionTemplates.Template::name).orElse(i.getTemplateCode()))
                .orElse("점검표 " + inspectionId);
    }

    @Override
    public void onApproved(Long inspectionId) {
        load(inspectionId).approve();
    }

    @Override
    public void onRejected(Long inspectionId) {
        Inspection i = load(inspectionId);
        i.reject();
        i.reopen();
    }

    @Override
    public void onCanceled(Long inspectionId) {
        load(inspectionId).withdraw();
    }

    private Inspection load(Long inspectionId) {
        return inspectionRepository.findById(inspectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "점검표를 찾을 수 없습니다."));
    }
}
