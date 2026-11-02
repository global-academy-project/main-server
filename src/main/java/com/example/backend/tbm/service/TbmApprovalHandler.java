package com.example.backend.tbm.service;

import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.service.ApprovalTargetHandler;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.tbm.entity.TbmRecord;
import com.example.backend.tbm.repository.TbmRecordRepository;
import org.springframework.stereotype.Component;

/**
 * TBM 확인 (실시 완료 후 1단계 결재, 계획서 7-5장). 확인되면 APPROVED(이후 수정 불가),
 * 반려되면 작성 중으로 되돌려 고쳐서 다시 실시 완료·요청한다. 회수하면 실시 완료 상태 그대로.
 */
@Component
public class TbmApprovalHandler implements ApprovalTargetHandler {

    private final TbmRecordRepository tbmRepository;

    public TbmApprovalHandler(TbmRecordRepository tbmRepository) {
        this.tbmRepository = tbmRepository;
    }

    @Override
    public ApprovalTarget type() {
        return ApprovalTarget.TBM;
    }

    @Override
    public String title(Long tbmId) {
        return tbmRepository.findById(tbmId).map(t -> t.getTbmDate() + " TBM"
                + (t.getLeaderName() == null ? "" : " · " + t.getLeaderName())).orElse("TBM " + tbmId);
    }

    @Override
    public void onApproved(Long tbmId) {
        load(tbmId).approve();
    }

    @Override
    public void onRejected(Long tbmId) {
        load(tbmId).reopen();
    }

    @Override
    public void onCanceled(Long tbmId) {
        // 실시 완료 상태 그대로 (다시 확인 요청할 수 있음)
    }

    private TbmRecord load(Long tbmId) {
        return tbmRepository.findById(tbmId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "TBM 기록을 찾을 수 없습니다."));
    }
}
