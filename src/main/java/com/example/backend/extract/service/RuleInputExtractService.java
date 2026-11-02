package com.example.backend.extract.service;

import com.example.backend.extract.dto.ExtractRequest;
import com.example.backend.extract.dto.ExtractResponse;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 문장 → 규칙 입력 추출 + 미리 판정 (계획서 7-4장 ②, ③은 기존 규칙 엔진 그대로).
 * 사용자가 확인하기 전 값이라 판정을 저장하지 않고 승인 요청과도 상관없다.
 * 확인한 값은 허가서 측정값(POST /permits/{id}/measurements)·양식(PUT /permits/{id})으로 넣고 정식 판정한다.
 */
@Service
public class RuleInputExtractService {

    private final RuleInputExtractor extractor;
    private final RuleCheckService ruleCheckService;
    private final WorkZoneRepository zoneRepository;
    private final SiteGuard siteGuard;

    public RuleInputExtractService(@Value("${app.extract.dictionary}") Resource dictionary, JsonMapper jsonMapper,
                                   RuleCheckService ruleCheckService, WorkZoneRepository zoneRepository,
                                   SiteGuard siteGuard) throws IOException {
        try (InputStream in = dictionary.getInputStream()) {
            this.extractor = new RuleInputExtractor(jsonMapper.readValue(in, ExtractDictionary.class));
        }
        this.ruleCheckService = ruleCheckService;
        this.zoneRepository = zoneRepository;
        this.siteGuard = siteGuard;
    }

    @Transactional(readOnly = true)
    public ExtractResponse extract(Long userId, ExtractRequest r) {
        siteGuard.requireAccess(userId, r.siteId());
        List<RuleInputExtractor.ZoneRef> zones = zoneRepository.findBySiteIdAndActiveTrueOrderByCodeAsc(r.siteId()).stream()
                .map(z -> new RuleInputExtractor.ZoneRef(z.getId(), z.getCode(), z.getName())).toList();
        RuleInputExtractor.Result result = extractor.extract(r.text(), zones);

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
        return new ExtractResponse(result, input, previews, "RULE:" + extractor.version());
    }
}
