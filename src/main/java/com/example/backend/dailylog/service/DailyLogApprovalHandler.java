package com.example.backend.dailylog.service;

import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.service.ApprovalTargetHandler;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.dailylog.entity.DailySafetyLog;
import com.example.backend.dailylog.repository.DailySafetyLogRepository;
import org.springframework.stereotype.Component;

/**
 * 일일 안전일지 결재 (계획서 7-5장 결재 표). 승인되면 APPROVED(이후 수정 불가),
 * 반려·회수되면 같은 안전일지를 작성 중(DRAFT)으로 되돌려 고친 뒤 다시 승인 요청한다.
 */
@Component
public class DailyLogApprovalHandler implements ApprovalTargetHandler {

    private final DailySafetyLogRepository logRepository;

    public DailyLogApprovalHandler(DailySafetyLogRepository logRepository) {
        this.logRepository = logRepository;
    }

    @Override
    public ApprovalTarget type() {
        return ApprovalTarget.DAILY_LOG;
    }

    @Override
    public String title(Long dailyLogId) {
        return logRepository.findById(dailyLogId).map(l -> l.getLogDate() + " 일일 안전일지").orElse("안전일지 " + dailyLogId);
    }

    @Override
    public void onApproved(Long dailyLogId) {
        load(dailyLogId).approve();
    }

    @Override
    public void onRejected(Long dailyLogId) {
        load(dailyLogId).reopen();
    }

    @Override
    public void onCanceled(Long dailyLogId) {
        load(dailyLogId).reopen();
    }

    private DailySafetyLog load(Long dailyLogId) {
        return logRepository.findById(dailyLogId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "안전일지를 찾을 수 없습니다."));
    }
}
