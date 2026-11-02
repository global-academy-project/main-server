package com.example.backend.risk.service;

import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.service.ApprovalService;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.risk.dto.RiskAssessmentResponse;
import com.example.backend.risk.dto.SaveRiskAssessmentRequest;
import com.example.backend.risk.entity.AssessmentType;
import com.example.backend.risk.entity.RiskAssessment;
import com.example.backend.risk.entity.RiskAssessmentItem;
import com.example.backend.risk.entity.RiskMethod;
import com.example.backend.risk.entity.RiskStatus;
import com.example.backend.risk.repository.RiskAssessmentItemRepository;
import com.example.backend.risk.repository.RiskAssessmentRepository;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.tag.entity.TagAxis;
import com.example.backend.tag.repository.TagCodeRepository;
import com.example.backend.user.entity.User;
import com.example.backend.work.entity.Work;
import com.example.backend.work.repository.WorkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 일일 위험성평가 조회·작성·수정·새 판·승인 요청. 작업 하나에 판(revision)으로 이어진다.
 * <ul>
 *   <li>작성: 작업에 아직 없을 때만 (에이전트 초안이 있으면 그 초안을 수정)</li>
 *   <li>수정: 작성 중(DRAFT)만, 위험요인은 통째로 바꾼다. 등급은 계산 등급보다 높게 고르면 그 값, 아니면 계산 등급 (C-2, yaml app.risk.level)</li>
 *   <li>새 판: 최신 판이 반려(REJECTED)면 내용·위험요인을 복사한 다음 판(DRAFT)을 만들고 이전 판은 SUPERSEDED</li>
 *   <li>승인 요청: 작성 중 + 위험요인 1개 이상 → 결재 (작업허가 최종 승인 전에 승인돼야 함, 계획서 7-5장)</li>
 * </ul>
 */
@Service
public class RiskAssessmentService {

    private final RiskAssessmentRepository riskRepository;
    private final RiskAssessmentItemRepository itemRepository;
    private final WorkRepository workRepository;
    private final TagCodeRepository tagCodeRepository;
    private final RecordHistoryRepository historyRepository;
    private final RiskLevelPolicy levelPolicy;
    private final SiteGuard siteGuard;
    private final ApprovalService approvalService;
    private final JsonMapper jsonMapper;

    public RiskAssessmentService(RiskAssessmentRepository riskRepository, RiskAssessmentItemRepository itemRepository,
                                 WorkRepository workRepository, TagCodeRepository tagCodeRepository,
                                 RecordHistoryRepository historyRepository, RiskLevelPolicy levelPolicy,
                                 SiteGuard siteGuard, ApprovalService approvalService, JsonMapper jsonMapper) {
        this.riskRepository = riskRepository;
        this.itemRepository = itemRepository;
        this.workRepository = workRepository;
        this.tagCodeRepository = tagCodeRepository;
        this.historyRepository = historyRepository;
        this.levelPolicy = levelPolicy;
        this.siteGuard = siteGuard;
        this.approvalService = approvalService;
        this.jsonMapper = jsonMapper;
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

    // ------------------------------------------------------------------ 작성·수정·새 판

    /** 작업의 일일 위험성평가 작성 (1판). 본문의 siteId·workId 가 맞아야 하고, 이미 있으면 409 (수정하거나, 반려됐으면 새 판) */
    @Transactional
    public RiskAssessmentResponse create(Long userId, SaveRiskAssessmentRequest r) {
        if (r.siteId() == null || r.workId() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "siteId 와 workId 가 필요합니다.");
        }
        if (r.assessmentType() != null && r.assessmentType() != AssessmentType.DAILY) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지금은 일일(DAILY) 위험성평가만 작성할 수 있습니다.");
        }
        Long workId = r.workId();
        Work work = workRepository.findById(workId).orElseThrow(() -> new BusinessException(ErrorCode.WORK_NOT_FOUND));
        User user = siteGuard.requireAccess(userId, work.getSiteId());
        if (!work.getSiteId().equals(r.siteId())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "작업이 선택한 현장의 것이 아닙니다.");
        }
        if (riskRepository.findTopByWorkIdOrderByRevisionDesc(workId).isPresent()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이 작업의 위험성평가가 이미 있습니다. 그 평가를 수정해 주세요.");
        }
        String formData = formDataOf(r.formData(), null);   // 저장 전에 검사 (잘못된 본문이면 행을 만들지 않음)
        RiskAssessment risk = riskRepository.saveAndFlush(RiskAssessment.daily(work.getSiteId(), workId, r.assessDate(),
                user.getId()));
        applyContent(risk, r, formData);
        replaceItems(risk, r.items());
        history(risk, HistoryAction.CREATE, userId, r.items().size());
        return response(risk);
    }

    /** 작성 중 평가 수정 (내용·위험요인 통째로) */
    @Transactional
    public RiskAssessmentResponse update(Long userId, Long riskId, SaveRiskAssessmentRequest r) {
        RiskAssessment risk = load(userId, riskId);
        if (risk.getStatus() != RiskStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "작성 중인 위험성평가만 고칠 수 있습니다 (현재 " + risk.getStatus() + "). 승인 대기면 회수, 반려면 새 판을 만드세요.");
        }
        if (r.lockVersion() == null || r.lockVersion() != risk.getLockVersion()) {
            throw new BusinessException(ErrorCode.CONFLICT_MODIFIED);
        }
        applyContent(risk, r, formDataOf(r.formData(), risk.getFormData()));
        replaceItems(risk, r.items());
        history(risk, HistoryAction.UPDATE, userId, r.items().size());
        return response(risk);
    }

    /** 반려된 최신 판에서 새 판 (내용·위험요인 복사, DRAFT). 이전 판은 SUPERSEDED */
    @Transactional
    public RiskAssessmentResponse revise(Long userId, Long riskId) {
        RiskAssessment prev = load(userId, riskId);
        if (prev.getStatus() != RiskStatus.REJECTED) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "반려된 위험성평가만 새 판을 만들 수 있습니다 (현재 " + prev.getStatus() + ").");
        }
        RiskAssessment latest = prev.getWorkId() == null ? prev
                : riskRepository.findTopByWorkIdOrderByRevisionDesc(prev.getWorkId()).orElse(prev);
        if (!latest.getId().equals(prev.getId())) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이미 새 판이 있습니다 (" + latest.getRevision() + "판).");
        }
        RiskAssessment next = riskRepository.saveAndFlush(prev.newRevision(userId));
        List<RiskAssessmentItem> copies = new ArrayList<>();
        for (RiskAssessmentItem i : itemRepository.findByAssessmentIdOrderBySeqAsc(prev.getId())) {
            copies.add(new RiskAssessmentItem(next.getId(), i.getSeq(), i.getWorkStep(), i.getHazard(), i.getAccidentType(),
                    i.getFrequency(), i.getSeverity(), i.getRiskLevel(), i.getControls(), i.getResidualFrequency(),
                    i.getResidualSeverity()));
        }
        itemRepository.saveAll(copies);
        prev.supersede();
        riskRepository.saveAndFlush(prev);
        history(next, HistoryAction.CREATE, userId, copies.size());
        return response(next);
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

    // ------------------------------------------------------------------ 내부

    private void applyContent(RiskAssessment risk, SaveRiskAssessmentRequest r, String formData) {
        guard(() -> risk.updateContent(risk.getZoneId(), risk.getTitle(), r.assessDate(),
                risk.getMethod() == null ? RiskMethod.FREQ_SEVERITY : risk.getMethod(),
                risk.getFormSchemaVersion(), formData));
        riskRepository.saveAndFlush(risk);
    }

    /** formData: 비우면 기존 값, JSON 객체 또는 JSON 객체 문자열("{...}")만 받는다 */
    private String formDataOf(JsonNode value, String current) {
        if (value == null || value.isNull()) {
            return current;
        }
        JsonNode node = value;
        if (value.isString()) {
            try {
                node = jsonMapper.readTree(value.asString());
            } catch (JacksonException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "formData 는 JSON 객체여야 합니다.");
            }
        }
        if (node == null || !node.isObject()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "formData 는 JSON 객체여야 합니다.");
        }
        return jsonMapper.writeValueAsString(node);
    }

    /** 위험요인 통째로 바꾸기: 보낸 순서가 순번(seq 무시), 등급은 C-2(계산보다 높게 고르면 그 값), 발생형태는 태그 코드만 */
    private void replaceItems(RiskAssessment risk, List<SaveRiskAssessmentRequest.Item> items) {
        List<RiskAssessmentItem> rows = new ArrayList<>();
        int seq = 1;
        for (SaveRiskAssessmentRequest.Item i : items) {
            String accident = i.accidentType() == null || i.accidentType().isBlank() ? null : i.accidentType().trim();
            if (accident != null && !tagCodeRepository.existsByAxisAndCodeAndActiveTrue(TagAxis.ACCIDENT_TYPE, accident)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "모르는 발생형태 코드입니다: " + accident);
            }
            int s = seq++;
            RiskAssessmentItem[] holder = new RiskAssessmentItem[1];
            guard(() -> holder[0] = new RiskAssessmentItem(risk.getId(), s, i.workStep(), i.hazard(), accident,
                    i.frequency(), i.severity(), levelPolicy.resolve(i.frequency() * i.severity(), i.riskLevel()), i.controls(),
                    i.residualFrequency(), i.residualSeverity()));
            rows.add(holder[0]);
        }
        List<RiskAssessmentItem> old = itemRepository.findByAssessmentIdOrderBySeqAsc(risk.getId());
        if (!old.isEmpty()) {
            itemRepository.deleteAll(old);
            itemRepository.flush();   // 순번 유니크(uk_rai_seq): 지운 뒤 넣는다
        }
        itemRepository.saveAllAndFlush(rows);
    }

    private void history(RiskAssessment risk, HistoryAction action, Long userId, int items) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("revision", risk.getRevision());
        after.put("assessDate", risk.getAssessDate().toString());
        after.put("items", items);
        historyRepository.save(RecordHistory.byUser(risk.getSiteId(), HistoryTarget.RISK, risk.getId(), action, userId,
                null, jsonMapper.writeValueAsString(after)));
    }

    private RiskAssessment load(Long userId, Long riskId) {
        RiskAssessment risk = riskRepository.findById(riskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "위험성평가를 찾을 수 없습니다."));
        siteGuard.requireAccess(userId, risk.getSiteId());
        return risk;
    }

    private RiskAssessmentResponse response(RiskAssessment risk) {
        List<RiskAssessmentItem> items = itemRepository.findByAssessmentIdOrderBySeqAsc(risk.getId());
        return RiskAssessmentResponse.of(risk, items, levelPolicy::levelOf);
    }

    /** 엔티티 입력 검증 오류를 400 으로 */
    private static void guard(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_STATE, e.getMessage());
        }
    }
}
