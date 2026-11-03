package com.example.backend.extract.service;

import com.example.backend.permit.entity.MeasurementItem;
import com.example.backend.rule.entity.WorkType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 규칙 추출 + LLM 추출 합치기 (계획서 7-4장 2단계). LLM 값은 아래를 모두 지킬 때만 쓴다.
 * <ul>
 *   <li>원문에 근거 문구(evidence)가 그대로 있고, 그 문구 안에 값 숫자가 그대로 있다 (지어낸 값 차단)</li>
 *   <li>단위가 그 항목이 받는 단위다</li>
 *   <li>규칙 추출이 그 항목에 문제(단위 불일치·두 값·범위 밖)를 남겼으면 LLM 값으로 채우지 않는다</li>
 *   <li>규칙 추출과 값이 다르면 둘 다 비우고 사람에게 묻는다</li>
 * </ul>
 * 같으면 BOTH, LLM 만 찾았으면 LLM(확인 문구 추가), 규칙만이면 RULE.
 */
public final class ExtractionMerger {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    private static final Map<MeasurementItem, Set<String>> UNITS = new EnumMap<>(Map.of(
            MeasurementItem.O2, Set.of("PCT"), MeasurementItem.CO2, Set.of("PCT"),
            MeasurementItem.LEL, Set.of("PCT_LEL", "PCT"), MeasurementItem.H2S, Set.of("PPM"),
            MeasurementItem.CO, Set.of("PPM"), MeasurementItem.NH3, Set.of("PPM"), MeasurementItem.CL2, Set.of("PPM")));

    private ExtractionMerger() {
    }

    public static RuleInputExtractor.Result merge(RuleInputExtractor.Result rule, LlmExtraction llm) {
        if (llm == null) {
            return rule;
        }
        String text = rule.text();
        List<String> notes = new ArrayList<>(rule.notes());

        // ---- 측정값
        Map<MeasurementItem, RuleInputExtractor.Value> byItem = new LinkedHashMap<>();
        rule.measurements().forEach(v -> byItem.put(v.item(), v));
        Set<String> troubled = new HashSet<>();
        rule.notes().forEach(n -> troubled.add(n.split(":")[0]));
        for (LlmExtraction.Measurement m : llm.measurements() == null ? List.<LlmExtraction.Measurement>of() : llm.measurements()) {
            MeasurementItem item = itemOf(m.item());
            if (item == null || m.value() == null || m.evidence() == null || m.evidence().isBlank()) {
                continue;
            }
            String evidence = m.evidence().strip();
            if (!text.contains(evidence) || !containsNumber(evidence, m.value())) {
                notes.add(item + ": LLM 이 찾은 값(" + m.value().toPlainString() + ")이 원문에 그대로 없어 쓰지 않았습니다.");
                continue;
            }
            if (m.unit() == null || !UNITS.get(item).contains(m.unit())) {
                notes.add(item + ": LLM 이 찾은 값의 단위(" + m.unit() + ")가 맞지 않아 쓰지 않았습니다.");
                continue;
            }
            RuleInputExtractor.Value ruleValue = byItem.get(item);
            if (ruleValue != null) {
                if (ruleValue.value().compareTo(m.value()) == 0) {
                    byItem.put(item, new RuleInputExtractor.Value(item, ruleValue.value(), ruleValue.unit(),
                            ruleValue.evidence(), "BOTH"));
                } else {
                    byItem.remove(item);
                    notes.add(item + ": 규칙 추출(" + ruleValue.value().toPlainString() + ")과 LLM 추출("
                            + m.value().toPlainString() + ") 값이 달라 비워 둡니다. 확인해 주세요.");
                }
                continue;
            }
            if (troubled.contains(item.name())) {
                continue;   // 규칙 추출이 이미 문제를 남긴 항목은 LLM 으로 채우지 않는다
            }
            byItem.put(item, new RuleInputExtractor.Value(item, m.value(), item.unit(), evidence, "LLM"));
            notes.add(item + ": LLM 으로 찾은 값입니다 (\"" + evidence + "\"). 확인해 주세요.");
        }

        // ---- 작업 종류 (합집합, LLM 만 찾은 것은 원문 근거가 있을 때만)
        Map<WorkType, RuleInputExtractor.WorkTypeHit> types = new LinkedHashMap<>();
        rule.workTypes().forEach(t -> types.put(t.type(), t));
        for (LlmExtraction.WorkTypeHit t : llm.workTypes() == null ? List.<LlmExtraction.WorkTypeHit>of() : llm.workTypes()) {
            WorkType type = typeOf(t.type());
            if (type == null || type == WorkType.GENERAL || t.evidence() == null || !text.contains(t.evidence().strip())) {
                continue;
            }
            RuleInputExtractor.WorkTypeHit prev = types.get(type);
            types.put(type, prev == null ? new RuleInputExtractor.WorkTypeHit(type, t.evidence().strip(), "LLM")
                    : new RuleInputExtractor.WorkTypeHit(type, prev.evidence(), "BOTH"));
        }

        // ---- 감시자 (규칙 추출이 못 찾았고, 이름이 원문에 있을 때만)
        Map<String, Object> form = new LinkedHashMap<>(rule.form());
        String name = llm.watcherName() == null ? null : llm.watcherName().strip();
        if (!form.containsKey("watcher_name") && name != null && name.length() >= 2 && name.length() <= 20 && text.contains(name)) {
            form.put("watcher_name", name);
            notes.add("감시자: LLM 으로 찾은 이름입니다 (" + name + "). 확인해 주세요.");
        }

        List<RuleInputExtractor.WorkTypeHit> typeList = types.values().stream()
                .sorted(Comparator.comparing(RuleInputExtractor.WorkTypeHit::type)).toList();
        return new RuleInputExtractor.Result(text, typeList, rule.zoneId(), rule.zoneEvidence(),
                List.copyOf(byItem.values()), form, notes);
    }

    /** 근거 문구 안에 값 숫자가 그대로 있나 (17.5 ↔ "17.5", 18 ↔ "18" 또는 "18.0") */
    static boolean containsNumber(String evidence, BigDecimal value) {
        Matcher m = NUMBER.matcher(evidence);
        while (m.find()) {
            if (new BigDecimal(m.group()).compareTo(value) == 0) return true;
        }
        return false;
    }

    private static MeasurementItem itemOf(String s) {
        try {
            return s == null ? null : MeasurementItem.valueOf(s.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static WorkType typeOf(String s) {
        try {
            return s == null ? null : WorkType.valueOf(s.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
