package com.example.backend.risk.dto;

import com.example.backend.approval.entity.Approval;
import com.example.backend.approval.entity.ApprovalStatus;
import com.example.backend.risk.entity.AssessmentType;
import com.example.backend.risk.entity.RiskAssessment;
import com.example.backend.risk.entity.RiskAssessmentItem;
import com.example.backend.risk.entity.RiskLevel;
import com.example.backend.risk.entity.RiskStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/**
 * 위험성평가 조회 (에이전트 초안 포함). formData 는 초안 원문(JSON 문자열, 근거·검토 필요 표시 등).
 * canSubmit: 작성 중이고 위험요인이 1개 이상
 * items[].riskLevel: 저장된 등급, computedLevel: 빈도×강도로 서버가 계산한 등급 (다르면 사람이 올린 것)
 * approvalId·approvalStatus: 가장 최근 결재 (없으면 null)
 */
public record RiskAssessmentResponse(Long riskAssessmentId, Long siteId, Long workId, AssessmentType assessmentType,
                                     int revision, LocalDate assessDate, RiskStatus status, String formData,
                                     Long agentRunId, List<Item> items, boolean canSubmit, int lockVersion,
                                     Long approvalId, ApprovalStatus approvalStatus) {

    public record Item(int seq, String workStep, String hazard, String accidentType, int frequency, int severity,
                       Integer riskScore, RiskLevel riskLevel, String controls, Integer residualFrequency,
                       Integer residualSeverity, Integer residualScore, RiskLevel computedLevel) {

        static Item from(RiskAssessmentItem i, Function<Integer, RiskLevel> levelOf) {
            int score = i.getRiskScore() != null ? i.getRiskScore() : i.getFrequency() * i.getSeverity();
            return new Item(i.getSeq(), i.getWorkStep(), i.getHazard(), i.getAccidentType(), i.getFrequency(),
                    i.getSeverity(), score, i.getRiskLevel(), i.getControls(), i.getResidualFrequency(),
                    i.getResidualSeverity(), i.getResidualScore(), levelOf.apply(score));
        }
    }

    public static RiskAssessmentResponse of(RiskAssessment r, List<RiskAssessmentItem> items,
                                            Function<Integer, RiskLevel> levelOf, Approval approval) {
        return new RiskAssessmentResponse(r.getId(), r.getSiteId(), r.getWorkId(), r.getAssessmentType(), r.getRevision(),
                r.getAssessDate(), r.getStatus(), r.getFormData(), r.getAgentRunId(),
                items.stream().map(i -> Item.from(i, levelOf)).toList(),
                r.getStatus() == RiskStatus.DRAFT && !items.isEmpty(), r.getLockVersion(),
                approval == null ? null : approval.getId(), approval == null ? null : approval.getStatus());
    }
}
