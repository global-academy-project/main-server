package com.example.backend.approval.service;

import com.example.backend.approval.dto.ApprovalLine;
import com.example.backend.approval.entity.ApprovalPolicy;
import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.entity.ApproverRole;
import com.example.backend.approval.repository.ApprovalPolicyRepository;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.user.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 결재선 정책 (현장 × 문서 종류). 조회는 현장 권한, 변경은 현장 관리자 이상.
 * <ul>
 *   <li>결재를 처리하는 문서 종류(승인 처리기가 있는 종류: 작업허가·위험성평가·TBM)만 받는다</li>
 *   <li>통째로 바꾼다. 빈 목록이면 정책을 지워 기본 1단계(승인자)로 돌아간다</li>
 *   <li>단계 역할은 앞 단계보다 낮아질 수 없다 (승인자 → 현장 관리자 → 회사 관리자 순)</li>
 *   <li>진행 중인 결재는 요청 때 복사한 단계 그대로 (V6)</li>
 * </ul>
 */
@Service
public class ApprovalPolicyService {

    static final String DEFAULT_LABEL = "승인";

    private final ApprovalPolicyRepository policyRepository;
    private final RecordHistoryRepository historyRepository;
    private final SiteGuard siteGuard;
    private final JsonMapper jsonMapper;
    private final Set<ApprovalTarget> supported;

    public ApprovalPolicyService(ApprovalPolicyRepository policyRepository, RecordHistoryRepository historyRepository,
                                 SiteGuard siteGuard, JsonMapper jsonMapper, List<ApprovalTargetHandler> handlers) {
        this.policyRepository = policyRepository;
        this.historyRepository = historyRepository;
        this.siteGuard = siteGuard;
        this.jsonMapper = jsonMapper;
        Set<ApprovalTarget> types = EnumSet.noneOf(ApprovalTarget.class);
        handlers.forEach(h -> types.add(h.type()));
        this.supported = types;
    }

    /** 결재를 처리하는 모든 문서 종류의 결재선 (종류 이름 순) */
    @Transactional(readOnly = true)
    public List<ApprovalLine> list(Long userId, Long siteId) {
        siteGuard.requireAccess(userId, siteId);
        Map<ApprovalTarget, List<ApprovalPolicy>> saved = policyRepository.findBySiteIdOrderByTargetTypeAscStepNoAsc(siteId)
                .stream().collect(Collectors.groupingBy(ApprovalPolicy::getTargetType));
        return supported.stream().sorted(Comparator.comparing(Enum::name))
                .map(t -> line(t, saved.getOrDefault(t, List.of()))).toList();
    }

    @Transactional
    public ApprovalLine replace(Long userId, Long siteId, ApprovalTarget type, List<ApprovalLine.StepRequest> steps) {
        User user = siteGuard.requireSiteAdmin(userId, siteId);
        if (type == null || !supported.contains(type)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "결재선을 정할 수 없는 문서 종류입니다: " + type
                    + " (가능: " + supported + ")");
        }
        if (steps.size() > 10) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "결재 단계는 10개까지입니다.");
        }
        for (int i = 1; i < steps.size(); i++) {
            if (steps.get(i).requiredRole().ordinal() < steps.get(i - 1).requiredRole().ordinal()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        (i + 1) + "단계 역할(" + steps.get(i).requiredRole() + ")이 앞 단계(" + steps.get(i - 1).requiredRole()
                                + ")보다 낮습니다. 승인자 → 현장 관리자 → 회사 관리자 순으로 정해 주세요.");
            }
        }
        policyRepository.deleteBySiteIdAndTargetType(siteId, type);
        policyRepository.flush();
        List<ApprovalPolicy> rows = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            ApprovalLine.StepRequest s = steps.get(i);
            int no = i + 1;
            try {
                rows.add(new ApprovalPolicy(siteId, type, no, s.label(), s.requiredRole(), user.getId()));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, no + "단계: " + e.getMessage());
            }
        }
        policyRepository.saveAllAndFlush(rows);
        ApprovalLine line = line(type, rows);
        historyRepository.save(RecordHistory.byUser(siteId, HistoryTarget.APPROVAL_POLICY, siteId, HistoryAction.UPDATE,
                user.getId(), null, jsonMapper.writeValueAsString(line)));
        return line;
    }

    private static ApprovalLine line(ApprovalTarget type, List<ApprovalPolicy> rows) {
        if (rows.isEmpty()) {
            return new ApprovalLine(type, true, List.of(new ApprovalLine.Step(1, DEFAULT_LABEL, ApproverRole.APPROVER)));
        }
        return new ApprovalLine(type, false, rows.stream()
                .map(p -> new ApprovalLine.Step(p.getStepNo(), p.getLabel(), p.getRequiredRole())).toList());
    }
}
