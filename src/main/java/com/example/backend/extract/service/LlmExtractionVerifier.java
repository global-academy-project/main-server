package com.example.backend.extract.service;

import com.example.backend.permit.entity.MeasurementItem;
import com.example.backend.rule.entity.WorkType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 이 켜져 있을 때의 추출 (계획서 7-4장 2단계, 11/10 결정): LLM 이 문장을 규칙 입력 형식으로 바꾸고,
 * 서버는 그 값을 확인만 한 뒤 규칙 엔진으로 넘긴다. 사전(규칙) 추출 값은 섞지 않는다.
 * <ul>
 *   <li>측정값: 근거 문구가 원문에 그대로 있고 그 안에 값 숫자가 있어야 한다 (지어낸 값 차단)</li>
 *   <li>그 숫자가 원문에서 그 항목 이름 뒤(앞에 없으면 앞)에 있어야 한다. "산소 20.9%" 를 CO2 로 읽으면 버림.
 *       숫자 앞뒤에 항목 이름이 하나도 없으면 막지 않는다 (사전 추출도 이름 없는 숫자는 못 잡으므로)</li>
 *   <li>단위가 그 항목이 받는 단위(사전 units)이고, 근거 문구에 적힌 단위와도 맞아야 한다 ("황화수소 5%"를 ppm 으로 읽으면 버림)</li>
 *   <li>값이 0 이상 사전 max 이하여야 한다</li>
 *   <li>같은 항목에 값이 둘 이상이면 비운다</li>
 *   <li>작업 종류: 근거 문구가 원문에 있을 때만</li>
 *   <li>감시자: 이름(2~20자)이 원문에 그대로 있을 때만</li>
 *   <li>구역: LLM 에 묻지 않는다. 현장 구역 코드·이름 대조(사전 추출의 구역 결과)를 그대로 쓴다</li>
 * </ul>
 * 통과하지 못한 값은 비우고 notes 에 이유를 남긴다. 빈 값은 규칙 엔진이 "누락"으로 막는다.
 */
public final class LlmExtractionVerifier {

    static final String SOURCE = "LLM";
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    private LlmExtractionVerifier() {
    }

    /** 원문의 숫자 위치 → 그 숫자의 측정 항목 (RuleInputExtractor::itemAt). 이름이 없으면 null */
    @FunctionalInterface
    public interface ItemLocator {
        MeasurementItem itemAt(String text, int numStart, int numEnd);
    }

    /**
     * @param zone    사전 추출 결과 (구역과 구역 관련 안내만 쓴다)
     * @param rules   측정 항목별 사전 규칙 (받는 단위·최대값)
     * @param locator 원문 숫자 위치의 측정 항목 (항목을 바꿔 읽은 값 차단)
     */
    public static RuleInputExtractor.Result verify(RuleInputExtractor.Result zone, LlmExtraction llm,
                                                  Function<MeasurementItem, ExtractDictionary.Measurement> rules,
                                                  ItemLocator locator) {
        String text = zone.text();
        List<String> notes = new ArrayList<>();
        zone.notes().stream().filter(n -> n.startsWith("구역")).forEach(notes::add);

        // ---- 측정값
        Map<MeasurementItem, List<RuleInputExtractor.Value>> found = new EnumMap<>(MeasurementItem.class);
        for (LlmExtraction.Measurement m : llm.measurements() == null ? List.<LlmExtraction.Measurement>of() : llm.measurements()) {
            MeasurementItem item = itemOf(m.item());
            if (item == null || m.value() == null) {
                continue;
            }
            String evidence = m.evidence() == null ? "" : m.evidence().strip();
            if (evidence.isEmpty() || !text.contains(evidence) || !containsNumber(evidence, m.value())) {
                notes.add(item + ": 찾은 값(" + m.value().toPlainString() + ")이 문장에 그대로 없어 비워 둡니다.");
                continue;
            }
            int[] at = numberSpan(evidence, m.value());
            int base = text.indexOf(evidence);
            MeasurementItem named = locator.itemAt(text, base + at[0], base + at[1]);
            if (named != null && named != item) {
                notes.add(item + ": 값 " + m.value().toPlainString() + "은(는) 문장에서 " + named + " 값이라 비워 둡니다 (\""
                        + evidence + "\").");
                continue;
            }
            ExtractDictionary.Measurement rule = rules.apply(item);
            if (rule == null) {
                continue;
            }
            if (m.unit() == null || !rule.units().contains(m.unit()) || unitConflicts(evidence, m.unit())) {
                notes.add(item + ": 단위(" + m.unit() + ")가 이 항목 단위가 아니라 비워 둡니다 (\"" + evidence + "\").");
                continue;
            }
            if (m.value().signum() < 0 || m.value().compareTo(rule.max()) > 0) {
                notes.add(item + ": 값 " + m.value().toPlainString() + "이(가) 측정 범위를 벗어나 비워 둡니다.");
                continue;
            }
            found.computeIfAbsent(item, k -> new ArrayList<>())
                    .add(new RuleInputExtractor.Value(item, m.value(), item.unit(), evidence, SOURCE));
        }
        List<RuleInputExtractor.Value> measurements = new ArrayList<>();
        found.forEach((item, values) -> {
            Set<BigDecimal> distinct = new LinkedHashSet<>();
            values.forEach(v -> distinct.add(v.value().stripTrailingZeros()));
            if (distinct.size() > 1) {
                notes.add(item + ": 값이 둘 이상입니다 (" + String.join(", ", distinct.stream().map(BigDecimal::toPlainString).toList())
                        + "). 비워 둡니다.");
            } else {
                measurements.add(values.get(0));
            }
        });

        // ---- 작업 종류
        Map<WorkType, RuleInputExtractor.WorkTypeHit> types = new LinkedHashMap<>();
        for (LlmExtraction.WorkTypeHit t : llm.workTypes() == null ? List.<LlmExtraction.WorkTypeHit>of() : llm.workTypes()) {
            WorkType type = typeOf(t.type());
            String evidence = t.evidence() == null ? "" : t.evidence().strip();
            if (type == null || type == WorkType.GENERAL || evidence.isEmpty() || !text.contains(evidence)) {
                continue;
            }
            types.putIfAbsent(type, new RuleInputExtractor.WorkTypeHit(type, evidence, SOURCE));
        }

        // ---- 감시자
        Map<String, Object> form = new LinkedHashMap<>();
        String name = llm.watcherName() == null ? null : llm.watcherName().strip();
        if (name != null && !name.isEmpty()) {
            if (name.length() >= 2 && name.length() <= 20 && text.contains(name)) {
                form.put("watcher_name", name);
            } else {
                notes.add("감시자: 찾은 이름(" + name + ")이 문장에 그대로 없어 비워 둡니다.");
            }
        }

        List<RuleInputExtractor.WorkTypeHit> typeList = types.values().stream()
                .sorted(Comparator.comparing(RuleInputExtractor.WorkTypeHit::type)).toList();
        return new RuleInputExtractor.Result(text, typeList, zone.zoneId(), zone.zoneEvidence(),
                List.copyOf(measurements), form, notes);
    }

    /** 근거 문구에 적힌 단위가 LLM 이 준 단위와 다른가 (% ↔ ppm) */
    static boolean unitConflicts(String evidence, String unit) {
        String e = evidence.toLowerCase(java.util.Locale.ROOT);
        boolean saysPpm = e.contains("ppm") || e.contains("피피엠");
        boolean saysPct = e.contains("%") || e.contains("퍼센트") || e.contains("프로");
        return ("PPM".equals(unit) && saysPct && !saysPpm) || (!"PPM".equals(unit) && saysPpm && !saysPct);
    }

    /** 근거 문구 안에 값 숫자가 그대로 있나 (17.5 ↔ "17.5", 18 ↔ "18" 또는 "18.0") */
    static boolean containsNumber(String evidence, BigDecimal value) {
        return numberSpan(evidence, value) != null;
    }

    /** 근거 문구 안에서 값 숫자가 처음 나오는 위치 [시작, 끝). 없으면 null */
    private static int[] numberSpan(String evidence, BigDecimal value) {
        Matcher m = NUMBER.matcher(evidence);
        while (m.find()) {
            if (new BigDecimal(m.group()).compareTo(value) == 0) return new int[]{m.start(), m.end()};
        }
        return null;
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
