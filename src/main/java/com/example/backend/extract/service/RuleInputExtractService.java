package com.example.backend.extract.service;

import com.example.backend.extract.dto.ExtractRequest;
import com.example.backend.extract.dto.ExtractResponse;
import com.example.backend.permit.entity.MeasurementItem;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.rule.service.RuleCheckCommand;
import com.example.backend.rule.service.RuleCheckService;
import com.example.backend.site.repository.WorkZoneRepository;
import com.example.backend.site.service.SiteGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 문장 → 규칙 입력 추출 + 미리 판정 (계획서 7-4장 ②, ③은 기존 규칙 엔진 그대로).
 * 사용자가 확인하기 전 값이라 판정을 저장하지 않고 승인 요청과도 상관없다.
 * 확인한 값은 POST /permits/{id}/extraction 으로 넣고 정식 판정한다 (ExtractApplyService).
 * LLM 보강(app.extract.llm.client=fastapi): ai-model POST /api/v1/extract/rule-input (시나리오 키 "B", 로컬 GGUF 모델).
 */
@Service
public class RuleInputExtractService {

    private final RuleInputExtractor extractor;
    private final RuleCheckService ruleCheckService;
    private final WorkZoneRepository zoneRepository;
    private final SiteGuard siteGuard;
    private final LlmExtractClient llmClient;

    /** LLM 에 물어볼 측정 항목·작업 종류 (ai-model 요청 measurementItems·workTypes, 일반 작업은 제외) */
    static final List<String> LLM_ITEMS = Arrays.stream(MeasurementItem.values()).map(Enum::name).toList();
    static final List<String> LLM_WORK_TYPES = List.of(WorkType.HOT.name(), WorkType.CONFINED.name(), WorkType.HEIGHT.name());

    public RuleInputExtractService(@Value("${app.extract.dictionary}") Resource dictionary, JsonMapper jsonMapper,
                                   RuleCheckService ruleCheckService, WorkZoneRepository zoneRepository,
                                   SiteGuard siteGuard, LlmExtractClient llmClient) throws IOException {
        try (InputStream in = dictionary.getInputStream()) {
            this.extractor = new RuleInputExtractor(jsonMapper.readValue(in, ExtractDictionary.class));
        }
        this.ruleCheckService = ruleCheckService;
        this.zoneRepository = zoneRepository;
        this.siteGuard = siteGuard;
        this.llmClient = llmClient;
    }

    /** 규칙 추출만 (확인 저장 때 스냅샷에 남길 "서버가 뽑은 값") */
    public RuleInputExtractor.Result ruleOnly(String text, Long siteId) {
        List<RuleInputExtractor.ZoneRef> zones = zoneRepository.findBySiteIdAndActiveTrueOrderByCodeAsc(siteId).stream()
                .map(z -> new RuleInputExtractor.ZoneRef(z.getId(), z.getCode(), z.getName())).toList();
        return extractor.extract(text, zones);
    }

    public String ruleVersion() {
        return "RULE:" + extractor.version();
    }

    @Transactional(readOnly = true)
    public ExtractResponse extract(Long userId, ExtractRequest r) {
        siteGuard.requireAccess(userId, r.siteId());
        List<RuleInputExtractor.ZoneRef> zones = zoneRepository.findBySiteIdAndActiveTrueOrderByCodeAsc(r.siteId()).stream()
                .map(z -> new RuleInputExtractor.ZoneRef(z.getId(), z.getCode(), z.getName())).toList();
        RuleInputExtractor.Result ruleResult = extractor.extract(r.text(), zones);
        RuleInputExtractor.Result result = ruleResult;
        String name = "RULE:" + extractor.version();
        if (llmClient.name() != null) {
            var llm = llmClient.extract(ruleResult.text(), LLM_ITEMS, LLM_WORK_TYPES);
            if (llm.isPresent()) {
                result = ExtractionMerger.merge(ruleResult, llm.get());
                name += "+" + llmClient.name();
            } else {
                List<String> notes = new ArrayList<>(ruleResult.notes());
                notes.add("LLM 추출을 쓰지 못해 규칙 추출 결과만 보여 줍니다.");
                result = new RuleInputExtractor.Result(ruleResult.text(), ruleResult.workTypes(), ruleResult.zoneId(),
                        ruleResult.zoneEvidence(), ruleResult.measurements(), ruleResult.form(), notes);
            }
        }

        Map<String, Object> measurement = new LinkedHashMap<>();
        result.measurements().forEach(v -> measurement.put(v.item().name(), v.value()));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("measurement", measurement);
        input.put("form", new LinkedHashMap<>(result.form()));

        List<WorkType> types = r.workType() != null ? List.of(r.workType())
                : result.workTypes().stream().map(RuleInputExtractor.WorkTypeHit::type).toList();
        List<ExtractResponse.Preview> previews = new ArrayList<>();
        for (WorkType t : types) {
            previews.add(new ExtractResponse.Preview(t,
                    ruleCheckService.check(new RuleCheckCommand(r.siteId(), t, null, null, null, input))));
        }
        return new ExtractResponse(result, input, previews, name);
    }
}
