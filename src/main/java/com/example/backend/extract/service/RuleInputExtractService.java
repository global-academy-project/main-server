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
 * 추출 순서 (11/10 결정):
 * <ul>
 *   <li>LLM 켜짐(app.extract.llm.client=fastapi): ai-model 이 문장을 규칙 입력 형식으로 바꾸고, 서버가 확인만 한 뒤
 *       규칙 엔진으로 넘긴다 (LlmExtractionVerifier). 사전(규칙) 추출 값은 섞지 않는다. 구역만 현장 구역 대조를 쓴다</li>
 *   <li>LLM 꺼짐(none, 기본)이거나 LLM 응답 실패: 사전(규칙) 추출만 쓰고, 실패면 notes 에 알린다</li>
 * </ul>
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

    /** 추출 결과와 쓴 추출기 이름 (응답 extractor, 허가서 스냅샷 _source.extractor) */
    public record Extraction(RuleInputExtractor.Result result, String extractor) {
    }

    /**
     * 문장 → 추출 (위 순서). 미리 보기와 허가서 반영이 같은 결과를 쓰도록 한 곳에 둔다.
     * LLM 호출이 있을 수 있으니(최대 app.extract.llm.timeout-seconds) 쓰기 트랜잭션 안에서 부르지 않는다.
     */
    public Extraction run(String text, Long siteId) {
        List<RuleInputExtractor.ZoneRef> zones = zoneRepository.findBySiteIdAndActiveTrueOrderByCodeAsc(siteId).stream()
                .map(z -> new RuleInputExtractor.ZoneRef(z.getId(), z.getCode(), z.getName())).toList();
        RuleInputExtractor.Result ruleResult = extractor.extract(text, zones);
        String rule = "RULE:" + extractor.version();
        if (llmClient.name() == null) {
            return new Extraction(ruleResult, rule);
        }
        var llm = llmClient.extract(ruleResult.text(), LLM_ITEMS, LLM_WORK_TYPES);
        if (llm.isPresent()) {
            return new Extraction(LlmExtractionVerifier.verify(ruleResult, llm.get(), extractor::measurementRule), llmClient.name());
        }
        List<String> notes = new ArrayList<>(ruleResult.notes());
        notes.add("LLM 추출을 쓰지 못해 사전(규칙) 추출 결과만 보여 줍니다.");
        return new Extraction(new RuleInputExtractor.Result(ruleResult.text(), ruleResult.workTypes(), ruleResult.zoneId(),
                ruleResult.zoneEvidence(), ruleResult.measurements(), ruleResult.form(), notes), rule);
    }

    public ExtractResponse extract(Long userId, ExtractRequest r) {
        siteGuard.requireAccess(userId, r.siteId());
        Extraction extraction = run(r.text(), r.siteId());
        RuleInputExtractor.Result result = extraction.result();

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
        return new ExtractResponse(result, input, previews, extraction.extractor());
    }
}
