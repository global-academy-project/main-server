package com.example.backend.permit.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.permit.dto.AddMeasurementsRequest;
import com.example.backend.permit.dto.CreatePermitRequest;
import com.example.backend.permit.dto.PermitCheckResponse;
import com.example.backend.permit.dto.PermitResponse;
import com.example.backend.permit.dto.UpdatePermitRequest;
import com.example.backend.permit.entity.MeasurementItem;
import com.example.backend.permit.entity.Permit;
import com.example.backend.permit.entity.PermitMeasurement;
import com.example.backend.permit.entity.PermitStatus;
import com.example.backend.permit.repository.PermitMeasurementRepository;
import com.example.backend.permit.repository.PermitRepository;
import com.example.backend.rule.entity.EvaluationResult;
import com.example.backend.rule.entity.EvaluationTarget;
import com.example.backend.rule.entity.Rule;
import com.example.backend.rule.entity.RuleEvaluation;
import com.example.backend.rule.entity.RuleViolation;
import com.example.backend.rule.repository.RuleEvaluationRepository;
import com.example.backend.rule.repository.RuleRepository;
import com.example.backend.rule.repository.RuleViolationRepository;
import com.example.backend.rule.service.RuleCheckCommand;
import com.example.backend.rule.service.RuleCheckResult;
import com.example.backend.rule.service.RuleCheckService;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.user.entity.User;
import com.example.backend.work.entity.Work;
import com.example.backend.work.repository.WorkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 작업허가 (화기·밀폐·고소 공통).
 * 흐름: 작성(DRAFT) → 측정값 입력 → 판정(check) → BLOCK 이면 BLOCKED, 아니면 DRAFT → 승인 요청(submit, PENDING)
 *  - 내용이나 측정값이 바뀌면 최근 판정이 무효가 되어 다시 판정해야 승인 요청할 수 있다
 *  - 승인 요청은 "최근 판정이 있고 BLOCK 이 아니며 적용 규칙이 1개 이상"일 때만 (에이전트를 거치지 않는 요청도 여기서 막는다)
 *  - 승인 요청 뒤에는 내용·측정값을 바꿀 수 없다 (V9 트리거도 막음)
 * 승인(approvals) 기록과 결재 흐름은 승인 기능에서 붙인다.
 */
@Service
public class PermitService {

    private static final String PRE_WORK = "PRE_WORK";
    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PermitRepository permitRepository;
    private final PermitMeasurementRepository measurementRepository;
    private final WorkRepository workRepository;
    private final RuleCheckService ruleCheckService;
    private final RuleEvaluationRepository evaluationRepository;
    private final RuleViolationRepository violationRepository;
    private final RuleRepository ruleRepository;
    private final SiteGuard siteGuard;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public PermitService(PermitRepository permitRepository, PermitMeasurementRepository measurementRepository,
                         WorkRepository workRepository, RuleCheckService ruleCheckService,
                         RuleEvaluationRepository evaluationRepository, RuleViolationRepository violationRepository,
                         RuleRepository ruleRepository, SiteGuard siteGuard, JsonMapper jsonMapper, Clock clock) {
        this.permitRepository = permitRepository;
        this.measurementRepository = measurementRepository;
        this.workRepository = workRepository;
        this.ruleCheckService = ruleCheckService;
        this.evaluationRepository = evaluationRepository;
        this.violationRepository = violationRepository;
        this.ruleRepository = ruleRepository;
        this.siteGuard = siteGuard;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 작성·수정·조회

    @Transactional
    public PermitResponse create(Long userId, CreatePermitRequest r) {
        Work work = workRepository.findById(r.workId()).orElseThrow(() -> new BusinessException(ErrorCode.WORK_NOT_FOUND));
        User user = siteGuard.requireAccess(userId, work.getSiteId());
        checkPeriod(r.validFrom(), r.validTo());
        if (permitRepository.existsByWorkIdAndPermitTypeAndRevision(work.getId(), r.permitType(), 1)) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이 작업에 같은 종류의 허가서가 이미 있습니다.");
        }
        String prefix = r.permitType().name() + "-" + r.validFrom().format(NO_DATE) + "-";
        long seq = permitRepository.countBySiteIdAndPermitNoStartingWith(work.getSiteId(), prefix) + 1;
        Permit permit = permitRepository.saveAndFlush(new Permit(work.getSiteId(), work.getId(), r.permitType(),
                prefix + String.format("%03d", seq), r.validFrom(), r.validTo(), blankToNull(r.watcherName()),
                blankToNull(r.supervisorName()), toJson(r.form()), user.getId()));
        return toResponse(permit);
    }

    @Transactional
    public PermitResponse update(Long userId, Long permitId, UpdatePermitRequest r) {
        Permit permit = loadEditable(userId, permitId);
        if (permit.getLockVersion() != r.lockVersion()) {
            throw new BusinessException(ErrorCode.CONFLICT_MODIFIED);
        }
        checkPeriod(r.validFrom(), r.validTo());
        permit.updateContent(r.validFrom(), r.validTo(), blankToNull(r.watcherName()), blankToNull(r.supervisorName()),
                toJson(r.form()));
        permitRepository.saveAndFlush(permit);
        return toResponse(permit);
    }

    @Transactional(readOnly = true)
    public PermitResponse get(Long userId, Long permitId) {
        Permit permit = load(userId, permitId);
        return toResponse(permit);
    }

    // ------------------------------------------------------------------ 측정값

    /** 작업 전 측정값 추가. 같은 항목을 다시 넣으면 재측정으로 기록되고, 판정에는 가장 최근 값이 쓰인다 */
    @Transactional
    public PermitResponse addMeasurements(Long userId, Long permitId, AddMeasurementsRequest r) {
        Permit permit = loadEditable(userId, permitId);
        LocalDateTime now = LocalDateTime.now(clock);
        Set<MeasurementItem> seenInRequest = new HashSet<>();
        List<PermitMeasurement> rows = new ArrayList<>();
        for (AddMeasurementsRequest.Item i : r.items()) {
            if (!"PPM".equals(i.item().unit()) && i.value().compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, i.item() + " 값은 0~100 사이여야 합니다.");
            }
            boolean remeasure = !seenInRequest.add(i.item())
                    || measurementRepository.existsByPermitIdAndPhaseAndItem(permit.getId(), PRE_WORK, i.item());
            rows.add(new PermitMeasurement(permit.getId(), i.measuredAt() == null ? now : i.measuredAt(), i.item(),
                    i.value(), blankToNull(i.location()), blankToNull(i.measurerName()), remeasure));
        }
        measurementRepository.saveAll(rows);
        permit.invalidateEvaluation();
        permitRepository.saveAndFlush(permit);
        return toResponse(permit);
    }

    // ------------------------------------------------------------------ 판정·승인 요청

    /**
     * 규칙 판정. 입력 = 항목별 최근 측정값 + 양식 값(+ 감시자). 판정은 저장되고 허가의 최근 판정이 된다.
     * BLOCK 이면 BLOCKED, 아니면 DRAFT(승인 요청 가능).
     */
    @Transactional
    public PermitCheckResponse check(Long userId, Long permitId) {
        Permit permit = loadEditable(userId, permitId);
        RuleCheckResult result = ruleCheckService.check(new RuleCheckCommand(permit.getSiteId(),
                permit.getPermitType().toWorkType(), EvaluationTarget.PERMIT, permit.getId(), null, ruleInput(permit)));
        permit.applyEvaluation(result.evaluationId(), result.result() == EvaluationResult.BLOCK);
        permitRepository.saveAndFlush(permit);
        return new PermitCheckResponse(permit.getId(), permit.getStatus(),
                canSubmit(permit, result.result(), result.rulesApplied()), result);
    }

    /** 승인 요청. 최근 판정이 없거나(내용이 바뀜) BLOCK 이면 INVALID_STATE */
    @Transactional
    public PermitResponse submit(Long userId, Long permitId) {
        Permit permit = load(userId, permitId);
        if (permit.getStatus() == PermitStatus.BLOCKED) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "규칙 판정이 BLOCK 이라 승인 요청할 수 없습니다. 조치 후 다시 판정하세요.");
        }
        if (permit.getStatus() != PermitStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "작성 중인 허가서만 승인 요청할 수 있습니다.");
        }
        if (permit.getLastEvaluationId() == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "규칙 판정을 먼저 해야 합니다. (내용이나 측정값이 바뀌면 다시 판정)");
        }
        RuleEvaluation evaluation = evaluationRepository.findById(permit.getLastEvaluationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STATE, "규칙 판정을 먼저 해야 합니다."));
        if (evaluation.getResult() == EvaluationResult.BLOCK) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "규칙 판정이 BLOCK 이라 승인 요청할 수 없습니다.");
        }
        if (evaluation.getRulesApplied() == 0) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이 허가 종류는 아직 판정 규칙이 없어 승인 요청할 수 없습니다.");
        }
        permit.submit();
        permitRepository.saveAndFlush(permit);
        return toResponse(permit);
    }

    // ------------------------------------------------------------------ 내부

    /** 규칙 입력: measurement = 항목별 최근 작업 전 측정값, form = 양식 값 + watcher_name (감시자 컬럼) */
    Map<String, Object> ruleInput(Permit permit) {
        Map<String, Object> measurement = new LinkedHashMap<>();
        for (PermitMeasurement m : measurementRepository.findByPermitIdAndPhaseOrderByMeasuredAtAscIdAsc(permit.getId(), PRE_WORK)) {
            measurement.put(m.getItem().name(), m.getValue());   // 시각 순이라 마지막 값(최근 측정)이 남는다
        }
        Map<String, Object> form = new LinkedHashMap<>(fromJson(permit.getFormData()));
        form.put("watcher_name", permit.getWatcherName());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("measurement", measurement);
        input.put("form", form);
        return input;
    }

    private Permit load(Long userId, Long permitId) {
        Permit permit = permitRepository.findById(permitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PERMIT_NOT_FOUND));
        siteGuard.requireAccess(userId, permit.getSiteId());
        return permit;
    }

    private Permit loadEditable(Long userId, Long permitId) {
        Permit permit = load(userId, permitId);
        if (!permit.getStatus().isEditable()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "승인 요청된 허가서는 고칠 수 없습니다.");
        }
        return permit;
    }

    /** 승인 요청 가능: 작성 중 + 최근 판정 있음 + BLOCK 아님 + 적용 규칙 1개 이상 (규칙이 없는 허가는 판정 불가) */
    private static boolean canSubmit(Permit permit, EvaluationResult result, int rulesApplied) {
        return permit.getStatus() == PermitStatus.DRAFT && permit.getLastEvaluationId() != null
                && result != EvaluationResult.BLOCK && rulesApplied > 0;
    }

    private PermitResponse toResponse(Permit p) {
        List<PermitResponse.Measurement> measurements = measurementRepository
                .findByPermitIdAndPhaseOrderByMeasuredAtAscIdAsc(p.getId(), PRE_WORK).stream()
                .map(PermitResponse.Measurement::from).toList();
        PermitResponse.Evaluation evaluation = null;
        EvaluationResult result = null;
        int rulesApplied = 0;
        if (p.getLastEvaluationId() != null) {
            RuleEvaluation e = evaluationRepository.findById(p.getLastEvaluationId()).orElse(null);
            if (e != null) {
                result = e.getResult();
                rulesApplied = e.getRulesApplied();
                evaluation = new PermitResponse.Evaluation(e.getId(), e.getResult(), e.getEvaluatedAt(), violations(e.getId()));
            }
        }
        return new PermitResponse(p.getId(), p.getSiteId(), p.getWorkId(), p.getPermitType(), p.getRevision(),
                p.getPermitNo(), p.getValidFrom(), p.getValidTo(), p.getStatus(), p.getWatcherName(),
                p.getSupervisorName(), fromJson(p.getFormData()), measurements, evaluation,
                evaluation != null && canSubmit(p, result, rulesApplied), p.getLockVersion());
    }

    private List<PermitResponse.Violation> violations(Long evaluationId) {
        List<RuleViolation> rows = violationRepository.findByEvaluationIdOrderByIdAsc(evaluationId);
        Map<Long, String> codes = ruleRepository.findAllById(rows.stream().map(RuleViolation::getRuleId).toList())
                .stream().collect(Collectors.toMap(Rule::getId, Rule::getRuleCode, (a, b) -> a));
        return rows.stream().map(v -> new PermitResponse.Violation(codes.get(v.getRuleId()), v.getActualValue(),
                v.getExpected(), v.getSeverity(), v.getMessage(), v.getLegalBasis())).toList();
    }

    private static void checkPeriod(LocalDateTime from, LocalDateTime to) {
        if (!to.isAfter(from)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "허가 종료 시각은 시작 시각보다 뒤여야 합니다.");
        }
    }

    private String toJson(Map<String, Object> form) {
        return form == null || form.isEmpty() ? null : jsonMapper.writeValueAsString(form);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        return jsonMapper.readValue(json, Map.class);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
