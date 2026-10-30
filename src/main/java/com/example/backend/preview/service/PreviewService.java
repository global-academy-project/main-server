package com.example.backend.preview.service;

import com.example.backend.approval.dto.ApprovalDetail;
import com.example.backend.approval.entity.Approval;
import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.repository.ApprovalRepository;
import com.example.backend.approval.service.ApprovalService;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.permit.dto.PermitResponse;
import com.example.backend.permit.entity.Permit;
import com.example.backend.permit.entity.PermitStatus;
import com.example.backend.permit.entity.PermitType;
import com.example.backend.permit.repository.PermitRepository;
import com.example.backend.permit.service.PermitService;
import com.example.backend.preview.dto.PreviewMark;
import com.example.backend.preview.dto.PreviewResponse;
import com.example.backend.risk.dto.RiskAssessmentResponse;
import com.example.backend.risk.entity.RiskAssessment;
import com.example.backend.risk.entity.RiskStatus;
import com.example.backend.risk.repository.RiskAssessmentRepository;
import com.example.backend.risk.service.RiskAssessmentService;
import com.example.backend.tbm.dto.TbmResponse;
import com.example.backend.tbm.entity.TbmStatus;
import com.example.backend.tbm.service.TbmService;
import com.example.backend.work.dto.WorkResponse;
import com.example.backend.work.service.WorkService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 문서 미리보기 (계획서 7-5장). 읽기만 하고 아무것도 저장하지 않는다.
 * 권한은 각 문서 조회와 같다 (그 현장 사람). 승인 대기 중에는 내용이 잠겨 있어 결재자가 본 내용이 승인 후 출력과 같다.
 */
@Service
public class PreviewService {

    private final PermitService permitService;
    private final PermitRepository permitRepository;
    private final RiskAssessmentService riskService;
    private final RiskAssessmentRepository riskRepository;
    private final TbmService tbmService;
    private final WorkService workService;
    private final ApprovalService approvalService;
    private final ApprovalRepository approvalRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public PreviewService(PermitService permitService, PermitRepository permitRepository,
                          RiskAssessmentService riskService, RiskAssessmentRepository riskRepository,
                          TbmService tbmService, WorkService workService,
                          ApprovalService approvalService, ApprovalRepository approvalRepository,
                          JsonMapper jsonMapper, Clock clock) {
        this.permitService = permitService;
        this.permitRepository = permitRepository;
        this.riskService = riskService;
        this.riskRepository = riskRepository;
        this.tbmService = tbmService;
        this.workService = workService;
        this.approvalService = approvalService;
        this.approvalRepository = approvalRepository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** 결재 상세에서: 그 결재의 대상 문서 */
    @Transactional(readOnly = true)
    public PreviewResponse ofApproval(Long userId, Long approvalId) {
        ApprovalDetail approval = approvalService.get(userId, approvalId);   // 현장 권한 확인
        return build(userId, approval.summary().targetType(), approval.summary().targetId(), approval);
    }

    /** 문서 화면에서: 작성 중 문서도 볼 수 있음. 결재가 있으면 가장 최근 결재를 붙인다 */
    @Transactional(readOnly = true)
    public PreviewResponse ofTarget(Long userId, ApprovalTarget type, Long targetId) {
        PreviewResponse preview = build(userId, type, targetId, null);   // 문서 조회가 현장 권한 확인
        ApprovalDetail approval = approvalRepository.findByTargetTypeAndTargetIdOrderByRequestedAtDescIdDesc(type, targetId)
                .stream().findFirst().map(Approval::getId).map(id -> approvalService.get(userId, id)).orElse(null);
        return approval == null ? preview : withApproval(preview, approval);
    }

    private PreviewResponse build(Long userId, ApprovalTarget type, Long targetId, ApprovalDetail approval) {
        LocalDateTime now = LocalDateTime.now(clock);
        return switch (type) {
            case PERMIT -> {
                PermitResponse p = permitService.get(userId, targetId);
                String version = permitRepository.findById(targetId).map(Permit::getFormSchemaVersion).orElse(null);
                PreviewMark mark = markOf(p.status());
                yield new PreviewResponse("PERMIT", targetId, formTypeOf(p.permitType()), version, p.status().name(),
                        mark, mark == null ? null : mark.text(), mark == null,
                        p.permitNo() + " " + labelOf(p.permitType()), workService.get(userId, p.workId()),
                        jsonMapper.valueToTree(p), approval, now);
            }
            case RISK -> {
                RiskAssessmentResponse r = riskService.get(userId, targetId);
                PreviewMark mark = markOf(r.status());
                WorkResponse work = r.workId() == null ? null : workService.get(userId, r.workId());
                yield new PreviewResponse("RISK", targetId, "RISK_ASSESSMENT", riskRepository.findById(targetId).map(RiskAssessment::getFormSchemaVersion).orElse(null), r.status().name(),
                        mark, mark == null ? null : mark.text(), mark == null,
                        "위험성평가" + (work == null ? "" : " · " + work.title()), work, riskContent(r), approval, now);
            }
            case TBM -> {
                TbmResponse t = tbmService.get(userId, targetId);
                PreviewMark mark = markOf(t.status());
                yield new PreviewResponse("TBM", targetId, "TBM", null, t.status().name(), mark,
                        mark == null ? null : mark.text(), mark == null, t.tbmDate() + " TBM", null,
                        jsonMapper.valueToTree(t), approval, now);
            }
            default -> throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "미리보기는 작업허가·위험성평가·TBM 만 지원합니다: " + type);
        };
    }

    private PreviewResponse withApproval(PreviewResponse p, ApprovalDetail approval) {
        return new PreviewResponse(p.targetType(), p.targetId(), p.formType(), p.formSchemaVersion(), p.status(), p.mark(),
                p.markText(), p.official(), p.title(), p.work(), p.content(), approval, p.generatedAt());
    }

    /** 위험성평가 formData(JSON 문자열)를 JSON 으로 풀어 둔다. 풀 수 없으면 문자열 그대로 */
    private JsonNode riskContent(RiskAssessmentResponse r) {
        ObjectNode node = jsonMapper.valueToTree(r);
        if (r.formData() != null) {
            try {
                node.set("formData", jsonMapper.readTree(r.formData()));
            } catch (JacksonException e) {
                // 문자열 그대로
            }
        }
        return node;
    }

    // ------------------------------------------------------------------ 표시 문구

    static PreviewMark markOf(PermitStatus s) {
        return switch (s) {
            case DRAFT, CHECKING, BLOCKED -> PreviewMark.DRAFT;
            case PENDING -> PreviewMark.PENDING;
            case REJECTED -> PreviewMark.REJECTED;
            case SUPERSEDED -> PreviewMark.SUPERSEDED;
            case APPROVED, SUSPENDED, CLOSED -> null;
        };
    }

    static PreviewMark markOf(RiskStatus s) {
        return switch (s) {
            case DRAFT -> PreviewMark.DRAFT;
            case PENDING -> PreviewMark.PENDING;
            case REJECTED -> PreviewMark.REJECTED;
            case SUPERSEDED -> PreviewMark.SUPERSEDED;
            case APPROVED -> null;
        };
    }

    /** TBM: 실시 완료(DONE)는 확인 전이라 승인 전 미리보기 */
    static PreviewMark markOf(TbmStatus s) {
        return switch (s) {
            case DRAFT -> PreviewMark.DRAFT;
            case DONE -> PreviewMark.PENDING;
            case APPROVED -> null;
        };
    }

    static String formTypeOf(PermitType t) {
        return switch (t) {
            case HOT -> "HOT_WORK_PERMIT";
            case CONFINED -> "CONFINED_SPACE_PERMIT";
            case HEIGHT -> "HEIGHT_WORK_PERMIT";
        };
    }

    private static String labelOf(PermitType t) {
        return switch (t) {
            case HOT -> "화기작업허가";
            case CONFINED -> "밀폐공간작업허가";
            case HEIGHT -> "고소작업허가";
        };
    }
}
