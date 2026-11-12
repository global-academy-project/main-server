package com.example.backend.extract.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.extract.dto.ApplyExtractionRequest;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.permit.dto.AddMeasurementsRequest;
import com.example.backend.permit.dto.PermitResponse;
import com.example.backend.permit.dto.UpdatePermitRequest;
import com.example.backend.permit.entity.Permit;
import com.example.backend.permit.repository.PermitRepository;
import com.example.backend.permit.service.PermitService;
import com.example.backend.rule.entity.Rule;
import com.example.backend.rule.repository.RuleRepository;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.user.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 확인한 추출값을 허가서에 넣고 원문·추출값 스냅샷을 남긴다 (계획서 7-4장 "무슨 말을 듣고 막았나").
 * <ul>
 *   <li>측정값은 작업 전 측정값으로 추가 (기존 측정값 API 와 같음), 양식 값은 합친다</li>
 *   <li>양식 form._source 에 {text, extractor, extracted(서버 추출: LLM 켜짐이면 LLM 확인값, 아니면 사전), confirmed(사용자 확인값), confirmedBy, confirmedAt}</li>
 *   <li>추출(LLM 호출 가능)은 저장 트랜잭션 밖에서 먼저 한다: 컨트롤러가 extractFor → apply 순서로 부른다</li>
 *   <li>판정 입력에는 source 로 따로 들어가 판정 스냅샷(rule_evaluations.input_json)에 함께 남는다</li>
 *   <li>최근 판정은 무효가 되므로 다시 판정해야 승인 요청할 수 있다</li>
 * </ul>
 */
@Service
public class ExtractApplyService {

    static final String SOURCE_KEY = "_source";   // PermitService.SOURCE_KEY 와 같은 값

    private final PermitService permitService;
    private final PermitRepository permitRepository;
    private final RuleRepository ruleRepository;
    private final RuleInputExtractService extractService;
    private final RecordHistoryRepository historyRepository;
    private final SiteGuard siteGuard;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public ExtractApplyService(PermitService permitService, PermitRepository permitRepository, RuleRepository ruleRepository,
                               RuleInputExtractService extractService, RecordHistoryRepository historyRepository,
                               SiteGuard siteGuard, JsonMapper jsonMapper, Clock clock) {
        this.permitService = permitService;
        this.permitRepository = permitRepository;
        this.ruleRepository = ruleRepository;
        this.extractService = extractService;
        this.historyRepository = historyRepository;
        this.siteGuard = siteGuard;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * 반영 전에 서버 추출을 다시 한다 (미리 보기와 같은 순서, 트랜잭션 밖). 권한 확인 포함.
     * LLM 은 부르지 않는다 (11/12): 미리 보기 때 받은 응답을 그대로 쓰고(LlmExtractCache, 실패였으면 실패 그대로),
     * 기억이 없으면 사전(규칙) 추출로 기록한다 (RuleInputExtractService.recorded). 반영은 LLM 을 기다리지 않는다
     */
    public RuleInputExtractService.Extraction extractFor(Long userId, Long permitId, String text) {
        return extractFor(userId, permitId, List.of(text));
    }

    /** 문장을 나눠 채운 경우: 문장마다 위와 같이 추출한 뒤 합친다 (뒤 문장이 같은 항목을 덮음, 화면과 같은 순서) */
    public RuleInputExtractService.Extraction extractFor(Long userId, Long permitId, List<String> texts) {
        Permit permit = permitRepository.findById(permitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PERMIT_NOT_FOUND));
        siteGuard.requireAccess(userId, permit.getSiteId());
        return RuleInputExtractService.merge(texts.stream().map(t -> extractService.recorded(t, permit.getSiteId())).toList());
    }

    @Transactional
    public PermitResponse apply(Long userId, Long permitId, ApplyExtractionRequest r, RuleInputExtractService.Extraction extraction) {
        Permit permit = permitRepository.findById(permitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PERMIT_NOT_FOUND));
        User user = siteGuard.requireAccess(userId, permit.getSiteId());
        Map<String, Object> form = r.form() == null ? Map.of() : r.form();
        Set<String> allowed = ruleRepository.findByEnabledTrueAndWorkTypeInOrderByRuleCodeAsc(
                        List.of(permit.getPermitType().toWorkType().name(), "ALL")).stream()
                .map(Rule::getTarget).filter(t -> t != null && t.startsWith("form."))
                .map(t -> t.substring("form.".length())).collect(Collectors.toSet());
        for (Map.Entry<String, Object> e : form.entrySet()) {
            if (!allowed.contains(e.getKey())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "이 허가 종류의 확인 항목이 아닙니다: " + e.getKey());
            }
            Object v = e.getValue();
            if (!(v == null || v instanceof String || v instanceof Boolean || v instanceof Number)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "확인 항목 값은 글자·숫자·true/false 만 됩니다: " + e.getKey());
            }
        }

        // 1) 양식 + 스냅샷 (lockVersion 확인은 기존 수정 API 가 한다)
        PermitResponse current = permitService.get(userId, permitId);
        Map<String, Object> merged = new LinkedHashMap<>(current.form() == null ? Map.of() : current.form());
        String watcher = current.watcherName();
        for (Map.Entry<String, Object> e : form.entrySet()) {
            if ("watcher_name".equals(e.getKey())) {
                watcher = e.getValue() == null ? null : String.valueOf(e.getValue());
            } else {
                merged.put(e.getKey(), e.getValue());
            }
        }
        RuleInputExtractor.Result extracted = extraction.result();
        Map<String, Object> extractedValues = new LinkedHashMap<>();
        extracted.measurements().forEach(v -> extractedValues.put(v.item().name(), v.value()));
        Map<String, Object> confirmed = new LinkedHashMap<>();
        Map<String, Object> confirmedMeasurement = new LinkedHashMap<>();
        (r.measurements() == null ? List.<ApplyExtractionRequest.Measurement>of() : r.measurements())
                .forEach(m -> confirmedMeasurement.put(m.item().name(), m.value()));
        confirmed.put("measurement", confirmedMeasurement);
        confirmed.put("form", new LinkedHashMap<>(form));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("text", r.text().strip());
        if (r.texts() != null && !r.texts().isEmpty()) {
            source.put("texts", r.texts().stream().map(String::strip).toList());   // 나눠 보낸 문장들 (11/12)
        }
        source.put("extractor", extraction.extractor());
        source.put("extracted", Map.of("measurement", extractedValues, "form", extracted.form(),
                "workTypes", extracted.workTypes().stream().map(t -> t.type().name()).toList()));
        source.put("confirmed", confirmed);
        source.put("confirmedBy", user.getId());
        source.put("confirmedAt", LocalDateTime.now(clock).toString());
        merged.put(SOURCE_KEY, source);
        PermitResponse updated = permitService.update(userId, permitId, new UpdatePermitRequest(current.validFrom(),
                current.validTo(), watcher, current.supervisorName(), merged, r.lockVersion()));

        // 2) 측정값 추가
        if (!confirmedMeasurement.isEmpty()) {
            updated = permitService.addMeasurements(userId, permitId, new AddMeasurementsRequest(r.measurements().stream()
                    .map(m -> new AddMeasurementsRequest.Item(m.item(), m.value(), null, null, user.getName())).toList()));
        }
        historyRepository.save(RecordHistory.byUser(permit.getSiteId(), HistoryTarget.PERMIT, permitId, HistoryAction.UPDATE,
                user.getId(), null, jsonMapper.writeValueAsString(Map.of("extraction", source))));
        return updated;
    }
}
