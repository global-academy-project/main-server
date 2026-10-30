package com.example.backend.rule.service;

import com.example.backend.permit.entity.MeasurementItem;
import com.example.backend.permit.entity.PermitType;
import com.example.backend.rule.entity.Rule;
import com.example.backend.rule.repository.RuleRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 허가 종류별 작업 전 측정 항목 = 켜진 MEASUREMENT 규칙의 measurement.{항목}.
 * 규칙 데이터가 기준이라 규칙이 바뀌면 대시보드·기록 조회도 같이 바뀐다 (항목을 코드에 두지 않음).
 */
@Component
public class MeasurementRequirements {

    private static final String PREFIX = "measurement.";

    private final RuleRepository ruleRepository;

    public MeasurementRequirements(RuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    /** 키: 허가 종류 이름(HOT·CONFINED·HEIGHT). 측정 규칙이 없는 종류는 키가 없다 */
    @Transactional(readOnly = true)
    public Map<String, Set<MeasurementItem>> byPermitType() {
        List<String> types = Arrays.stream(PermitType.values()).map(Enum::name).toList();
        Map<String, Set<MeasurementItem>> out = new HashMap<>();
        for (Rule r : ruleRepository.findByEnabledTrueAndWorkTypeInOrderByRuleCodeAsc(types)) {
            if (!"MEASUREMENT".equals(r.getCategory()) || r.getTarget() == null || !r.getTarget().startsWith(PREFIX)) {
                continue;
            }
            try {
                MeasurementItem item = MeasurementItem.valueOf(r.getTarget().substring(PREFIX.length()));
                out.computeIfAbsent(r.getWorkType(), k -> EnumSet.noneOf(MeasurementItem.class)).add(item);
            } catch (IllegalArgumentException e) {
                // 측정 항목 목록에 없는 규칙 대상은 세지 않는다
            }
        }
        return out;
    }
}
