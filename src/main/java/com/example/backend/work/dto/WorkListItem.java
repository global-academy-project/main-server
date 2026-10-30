package com.example.backend.work.dto;

import com.example.backend.permit.entity.PermitStatus;
import com.example.backend.permit.entity.PermitType;
import com.example.backend.risk.entity.RiskStatus;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.tbm.entity.TbmStatus;
import com.example.backend.work.entity.Work;
import com.example.backend.work.entity.WorkStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 작업 목록 한 줄. 작업 정보 + 붙은 문서 상태 (화면에서 바로 허가서·위험성평가·TBM 으로 이동).
 *  - permits: 종류별 최신 판
 *  - riskAssessment / tbm: 최신 판·최근 날짜 하나, 없으면 null
 */
public record WorkListItem(Long workId, Long siteId, LocalDate workDate, String title, WorkType workType,
                           boolean highRisk, LocalDateTime startAt, LocalDateTime endAt, Integer workerCount,
                           WorkStatus status, Long writerId, List<PermitBrief> permits, RiskBrief riskAssessment,
                           TbmBrief tbm) {

    public record PermitBrief(Long permitId, PermitType permitType, String permitNo, int revision, PermitStatus status) {
    }

    public record RiskBrief(Long riskAssessmentId, int revision, RiskStatus status) {
    }

    public record TbmBrief(Long tbmId, LocalDate tbmDate, TbmStatus status) {
    }

    public static WorkListItem of(Work w, List<PermitBrief> permits, RiskBrief risk, TbmBrief tbm) {
        return new WorkListItem(w.getId(), w.getSiteId(), w.getWorkDate(), w.getTitle(), w.getWorkType(), w.isHighRisk(),
                w.getStartAt(), w.getEndAt(), w.getWorkerCount(), w.getStatus(), w.getWriterId(), permits, risk, tbm);
    }
}
