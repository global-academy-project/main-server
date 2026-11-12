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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final LlmExtractCache llmCache;

    /** LLM 에 물어볼 측정 항목·작업 종류 (ai-model 요청 measurementItems·workTypes, 일반 작업은 제외) */
    static final List<String> LLM_ITEMS = Arrays.stream(MeasurementItem.values()).map(Enum::name).toList();
    static final List<String> LLM_WORK_TYPES = List.of(WorkType.HOT.name(), WorkType.CONFINED.name(), WorkType.HEIGHT.name());

    public RuleInputExtractService(@Value("${app.extract.dictionary}") Resource dictionary, JsonMapper jsonMapper,
                                   RuleCheckService ruleCheckService, WorkZoneRepository zoneRepository,
                                   SiteGuard siteGuard, LlmExtractClient llmClient, LlmExtractCache llmCache) throws IOException {
        try (InputStream in = dictionary.getInputStream()) {
            this.extractor = new RuleInputExtractor(jsonMapper.readValue(in, ExtractDictionary.class));
        }
        this.ruleCheckService = ruleCheckService;
        this.zoneRepository = zoneRepository;
        this.siteGuard = siteGuard;
        this.llmClient = llmClient;
        this.llmCache = llmCache;
    }

    /** 추출 결과와 쓴 추출기 이름 (응답 extractor, 허가서 스냅샷 _source.extractor) */
    public record Extraction(RuleInputExtractor.Result result, String extractor) {
    }

    /**
     * 문장 → 추출 (위 순서). 미리 보기와 허가서 반영이 같은 결과를 쓰도록 한 곳에 둔다.
     * LLM 호출이 있을 수 있으니(최대 app.extract.llm.timeout-seconds) 쓰기 트랜잭션 안에서 부르지 않는다.
     * 미리 보기용: LLM 응답을 기억해 두고(LlmExtractCache), 기억한 응답이 실패면 다시 부른다.
     */
    public Extraction run(String text, Long siteId) {
        return run(text, siteId, true);
    }

    /**
     * @param retryLlmFailure true(미리 보기): 기억한 LLM 응답이 실패면 다시 부른다.
     *                        false: 기억한 응답을 성공·실패 그대로 쓴다 (기억이 없으면 부른다).
     *                        허가서 반영은 11/12부터 LLM 을 부르지 않는 recorded() 를 쓴다
     */
    public Extraction run(String text, Long siteId, boolean retryLlmFailure) {
        RuleInputExtractor.Result ruleResult = ruleExtract(text, siteId);
        String rule = "RULE:" + extractor.version();
        if (llmClient.name() == null) {
            return new Extraction(ruleResult, rule);
        }
        var llm = llmCache.get(ruleResult.text(), retryLlmFailure,
                () -> llmClient.extract(ruleResult.text(), LLM_ITEMS, LLM_WORK_TYPES));
        if (llm.isPresent()) {
            return new Extraction(LlmExtractionVerifier.verify(ruleResult, llm.get(), extractor::measurementRule, extractor::itemAt), llmClient.name());
        }
        return new Extraction(withNote(ruleResult, "LLM 추출을 쓰지 못해 사전(규칙) 추출 결과만 보여 줍니다."), rule);
    }

    /**
     * 허가서 반영용 (11/12): LLM 을 부르지 않는다. 반영 요청이 LLM 을 기다리다 화면 대기(60초)를 넘겨
     * "저장 결과를 확인할 수 없음"이 되지 않게 하려고.
     * 미리 보기 때 기억한 LLM 응답이 있으면 그대로(성공·실패 그대로) 쓰고, 없으면(30분 지남·서버 재시작) 사전(규칙) 추출로 기록한다.
     * 저장되는 측정값은 사용자가 확인한 값이고, 이 결과는 "서버가 뽑은 값" 기록(_source.extracted)에만 쓴다.
     */
    public Extraction recorded(String text, Long siteId) {
        RuleInputExtractor.Result ruleResult = ruleExtract(text, siteId);
        String rule = "RULE:" + extractor.version();
        if (llmClient.name() == null) {
            return new Extraction(ruleResult, rule);
        }
        var cached = llmCache.peek(ruleResult.text());
        if (cached.isPresent() && cached.get().isPresent()) {
            return new Extraction(LlmExtractionVerifier.verify(ruleResult, cached.get().get(), extractor::measurementRule, extractor::itemAt), llmClient.name());
        }
        return new Extraction(withNote(ruleResult, cached.isPresent()
                ? "LLM 추출을 쓰지 못해 사전(규칙) 추출 결과만 보여 줍니다."
                : "미리 보기 때의 LLM 결과가 없어(30분이 지났거나 서버가 다시 시작됨) 사전(규칙) 추출 결과로 기록했습니다."), rule);
    }

    /**
     * 여러 문장의 추출을 하나로 합친다 (11/12, 문장을 나눠 채운 뒤 한 번에 반영할 때). 화면이 칸을 합치는 순서와 같게
     * 뒤 문장이 같은 항목·양식 값을 덮는다. 작업 종류는 처음 찾은 근거를 남기고, 구역은 처음 찾은 것을 쓴다.
     */
    public static Extraction merge(List<Extraction> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        Map<WorkType, RuleInputExtractor.WorkTypeHit> types = new LinkedHashMap<>();
        Map<MeasurementItem, RuleInputExtractor.Value> values = new LinkedHashMap<>();
        Map<String, Object> form = new LinkedHashMap<>();
        Set<String> notes = new LinkedHashSet<>();
        Set<String> extractors = new LinkedHashSet<>();
        List<String> texts = new ArrayList<>();
        Long zoneId = null;
        String zoneEvidence = null;
        for (Extraction part : parts) {
            RuleInputExtractor.Result r = part.result();
            texts.add(r.text());
            r.workTypes().forEach(t -> types.putIfAbsent(t.type(), t));
            r.measurements().forEach(v -> {
                values.remove(v.item());   // 뒤 문장 값이 순서도 뒤로
                values.put(v.item(), v);
            });
            form.putAll(r.form());
            notes.addAll(r.notes());
            extractors.add(part.extractor());
            if (zoneId == null && r.zoneId() != null) {
                zoneId = r.zoneId();
                zoneEvidence = r.zoneEvidence();
            }
        }
        return new Extraction(new RuleInputExtractor.Result(String.join("\n", texts), List.copyOf(types.values()), zoneId,
                zoneEvidence, List.copyOf(values.values()), form, List.copyOf(notes)), String.join(",", extractors));
    }

    private RuleInputExtractor.Result ruleExtract(String text, Long siteId) {
        List<RuleInputExtractor.ZoneRef> zones = zoneRepository.findBySiteIdAndActiveTrueOrderByCodeAsc(siteId).stream()
                .map(z -> new RuleInputExtractor.ZoneRef(z.getId(), z.getCode(), z.getName())).toList();
        return extractor.extract(text, zones);
    }

    private static RuleInputExtractor.Result withNote(RuleInputExtractor.Result r, String note) {
        List<String> notes = new ArrayList<>(r.notes());
        notes.add(note);
        return new RuleInputExtractor.Result(r.text(), r.workTypes(), r.zoneId(), r.zoneEvidence(), r.measurements(), r.form(), notes);
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
