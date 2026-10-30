package com.example.backend.risk.service;

import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.service.ApprovalService;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.risk.dto.RiskAssessmentResponse;
import com.example.backend.risk.entity.RiskAssessment;
import com.example.backend.risk.entity.RiskAssessmentItem;
import com.example.backend.risk.entity.RiskStatus;
import com.example.backend.risk.repository.RiskAssessmentItemRepository;
import com.example.backend.risk.repository.RiskAssessmentRepository;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.work.repository.WorkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 위험성평가 조회·승인 요청. 작성·수정 API 는 아직 없고, 지금 기록은 에이전트 초안 저장으로 만들어진다 (7-2장).
 * 작업허가 최종 승인 전에 위험성평가 승인이 필요해서(7-5장) 승인 요청까지 먼저 연다.
 */
@Service
public class RiskAssessmentService {

    private final RiskAssessmentRepository riskRepository;
    private final RiskAssessmentItemRepository itemRepository;
    private final WorkRepository workRepository;
    private final SiteGuard siteGuard;
    private final ApprovalService approvalService;

    public RiskAssessmentService(RiskAssessmentRepository riskRepository, RiskAssessmentItemRepository itemRepository,
                                 WorkRepository workRepository, SiteGuard siteGuard, ApprovalService approvalService) {
        this.riskRepository = riskRepository;
        this.itemRepository = itemRepository;
        this.workRepository = workRepository;
        this.siteGuard = siteGuard;
        this.approvalService = approvalService;
    }

    @Transactional(readOnly = true)
    public RiskAssessmentResponse get(Long userId, Long riskId) {
        RiskAssessment risk = load(userId, riskId);
        return response(risk);
    }

    /** 작업의 최신 판 위험성평가 */
    @Transactional(readOnly = true)
    public RiskAssessmentResponse getByWork(Long userId, Long workId) {
        var work = workRepository.findById(workId).orElseThrow(() -> new BusinessException(ErrorCode.WORK_NOT_FOUND));
        siteGuard.requireAccess(userId, work.getSiteId());
        RiskAssessment risk = riskRepository.findTopByWorkIdOrderByRevisionDesc(workId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "이 작업의 위험성평가가 없습니다."));
        return response(risk);
    }

    /** 승인 요청: 작성 중이고 위험요인이 1개 이상 */
    @Transactional
    public RiskAssessmentResponse submit(Long userId, Long riskId) {
        RiskAssessment risk = load(userId, riskId);
        if (risk.getStatus() != RiskStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "작성 중인 위험성평가만 승인 요청할 수 있습니다.");
        }
        if (itemRepository.findByAssessmentIdOrderBySeqAsc(riskId).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "위험요인을 1개 이상 작성한 뒤 승인 요청하세요.");
        }
        risk.submit();
        riskRepository.saveAndFlush(risk);
        approvalService.request(ApprovalTarget.RISK, risk.getId(), risk.getSiteId(), userId);
        return response(risk);
    }

    private RiskAssessment load(Long userId, Long riskId) {
        RiskAssessment risk = riskRepository.findById(riskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "위험성평가를 찾을 수 없습니다."));
        siteGuard.requireAccess(userId, risk.getSiteId());
        return risk;
    }

    private RiskAssessmentResponse response(RiskAssessment risk) {
        List<RiskAssessmentItem> items = itemRepository.findByAssessmentIdOrderBySeqAsc(risk.getId());
        return RiskAssessmentResponse.of(risk, items);
    }
}
