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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 문장에서 규칙 입력(작업 종류·구역·측정값·감시자)을 뽑는 규칙 기반 추출기 (계획서 7-4장).
 * <ul>
 *   <li>판정하지 않는다. 뽑기만 하고 판정은 규칙 엔진이 한다</li>
 *   <li>숫자를 추정하거나 바꾸지 않는다. 단위가 맞지 않거나 값이 둘 이상이면 비우고 notes 에 남긴다</li>
 *   <li>같은 문장이면 항상 같은 결과 (사전만 쓰고 LLM·DB 를 부르지 않음)</li>
 * </ul>
 */
public class RuleInputExtractor {

    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");
    private static final Pattern HEIGHT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(m|미터)(?![a-zA-Z])");
    private static final String PARTICLES = "이가은는을를도요야";

    /** 구역 후보 (현장 구역) */
    public record ZoneRef(Long id, String code, String name) {
    }

    /** source: RULE / LLM / BOTH */
    public record WorkTypeHit(WorkType type, String evidence, String source) {
    }

    /** source: RULE(사전) / LLM(LLM 만 찾음) / BOTH(둘이 같은 값) */
    public record Value(MeasurementItem item, BigDecimal value, String unit, String evidence, String source) {
    }

    public record Result(String text, List<WorkTypeHit> workTypes, Long zoneId, String zoneEvidence,
                         List<Value> measurements, Map<String, Object> form, List<String> notes) {
    }

    private record Name(ExtractDictionary.Measurement m, String name) {
    }

    private record Span(int start, int end, Name name) {
    }

    private final ExtractDictionary dict;
    private final List<Name> names;
    private final List<Map.Entry<String, String>> unitWords;   // 표기 → 단위 코드 (긴 표기부터)

    public RuleInputExtractor(ExtractDictionary dict) {
        this.dict = dict;
        List<Name> n = new ArrayList<>();
        for (ExtractDictionary.Measurement m : dict.measurements()) {
            MeasurementItem.valueOf(m.item());   // 사전 오타는 기동 때 실패
            m.names().forEach(x -> n.add(new Name(m, x)));
        }
        n.sort(Comparator.comparingInt((Name x) -> x.name().length()).reversed());
        this.names = List.copyOf(n);
        List<Map.Entry<String, String>> u = new ArrayList<>();
        dict.units().forEach((code, words) -> words.forEach(w -> u.add(Map.entry(w.toLowerCase(Locale.ROOT), code))));
        u.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
        this.unitWords = List.copyOf(u);
    }

    public String version() {
        return dict.version();
    }

    /** 측정 항목의 사전 규칙 (받는 단위·최대값). LLM 추출값 확인에 쓴다 (LlmExtractionVerifier) */
    public ExtractDictionary.Measurement measurementRule(MeasurementItem item) {
        return dict.measurements().stream().filter(m -> m.item().equals(item.name())).findFirst().orElse(null);
    }

    public Result extract(String text, List<ZoneRef> zones) {
        String t = text == null ? "" : text.strip();
        List<String> notes = new ArrayList<>();
        List<WorkTypeHit> types = workTypes(t);
        Map<String, Object> form = new LinkedHashMap<>();
        String watcher = watcher(t, notes);
        if (watcher != null) {
            form.put("watcher_name", watcher);
        }
        Long zoneId = null;
        String zoneEvidence = null;
        List<ZoneRef> hitZones = zones == null ? List.of() : zones.stream().filter(z -> mentions(t, z)).toList();
        if (hitZones.size() == 1) {
            zoneId = hitZones.get(0).id();
            zoneEvidence = hitZones.get(0).code() + " " + hitZones.get(0).name();
        } else if (hitZones.size() > 1) {
            notes.add("구역이 여러 개 나옵니다 (" + String.join(", ", hitZones.stream().map(ZoneRef::code).toList())
                    + "). 화면에서 골라 주세요.");
        }
        return new Result(t, types, zoneId, zoneEvidence, measurements(t, notes), form, notes);
    }

    // ------------------------------------------------------------------ 작업 종류

    private List<WorkTypeHit> workTypes(String t) {
        Map<WorkType, String> hits = new EnumMap<>(WorkType.class);
        String compact = t.replace(" ", "");
        dict.workTypes().forEach((type, words) -> {
            for (String w : words) {
                if (t.contains(w) || compact.contains(w.replace(" ", ""))) {
                    hits.putIfAbsent(WorkType.valueOf(type), w);
                    break;
                }
            }
        });
        Matcher h = HEIGHT.matcher(t);
        while (h.find()) {
            if (new BigDecimal(h.group(1)).compareTo(dict.heightMinMeters()) >= 0) {
                hits.putIfAbsent(WorkType.HEIGHT, h.group());
                break;
            }
        }
        List<WorkTypeHit> out = new ArrayList<>();
        hits.forEach((k, v) -> out.add(new WorkTypeHit(k, v, "RULE")));
        return out;
    }

    // ------------------------------------------------------------------ 측정값

    private List<Value> measurements(String t, List<String> notes) {
        List<Span> spans = spans(t);
        Map<MeasurementItem, List<Value>> found = new EnumMap<>(MeasurementItem.class);
        for (int i = 0; i < spans.size(); i++) {
            Span s = spans.get(i);
            int limit = Math.min(t.length(), s.end() + 20);
            if (i + 1 < spans.size()) {
                limit = Math.min(limit, spans.get(i + 1).start());
            }
            String window = t.substring(s.end(), limit);
            Matcher num = NUMBER.matcher(window);
            boolean ok = false;
            while (num.find()) {
                // 구역 코드(R-201)·영문 코드 안의 숫자는 측정값이 아니다
                char before = num.start() == 0 ? ' ' : window.charAt(num.start() - 1);
                if ((before != '-' && !Character.isLetter(before)) || before > 127) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                continue;
            }
            String after = window.substring(num.end());
            String afterTrim = after.stripLeading();
            if (dict.notMeasurementSuffixes().stream().anyMatch(x -> afterTrim.toLowerCase(Locale.ROOT).startsWith(x.toLowerCase(Locale.ROOT))
                    && !startsWithUnit(afterTrim))) {
                continue;
            }
            MeasurementItem item = MeasurementItem.valueOf(s.name().m().item());
            BigDecimal value = new BigDecimal(num.group(1));
            String unitCode = unitOf(afterTrim);
            String evidence = t.substring(s.start(), Math.min(t.length(), s.end() + num.end()
                    + (unitCode == null ? 0 : (after.length() - afterTrim.length()) + unitLength(afterTrim))));
            String expected = item.unit();
            if (unitCode == null) {
                notes.add(item + ": 단위가 없어 " + unitLabel(expected) + "(으)로 봤습니다. 확인해 주세요 (\"" + evidence + "\").");
            } else if (!s.name().m().units().contains(unitCode)) {
                notes.add(item + ": 단위가 " + unitLabel(unitCode) + "(으)로 왔습니다. 기준 단위는 " + unitLabel(expected)
                        + "라서 비워 둡니다 (\"" + evidence + "\").");
                continue;
            }
            if (value.signum() < 0 || value.compareTo(s.name().m().max()) > 0) {
                notes.add(item + ": 값 " + value.toPlainString() + "이(가) 측정 범위를 벗어나 비워 둡니다.");
                continue;
            }
            found.computeIfAbsent(item, k -> new ArrayList<>()).add(new Value(item, value, expected, evidence, "RULE"));
        }
        List<Value> out = new ArrayList<>();
        found.forEach((item, values) -> {
            Set<BigDecimal> distinct = new LinkedHashSet<>();
            values.forEach(v -> distinct.add(v.value().stripTrailingZeros()));
            if (distinct.size() > 1) {
                notes.add(item + ": 값이 둘 이상입니다 (" + String.join(", ", distinct.stream().map(BigDecimal::toPlainString).toList())
                        + "). 비워 둡니다.");
            } else {
                out.add(values.get(0));
            }
        });
        return out;
    }

    /** 측정 항목 이름 위치 (긴 이름 먼저, 겹치면 앞선 것만, 영문은 앞뒤가 영숫자가 아니어야) */
    private List<Span> spans(String t) {
        String upper = t.toUpperCase(Locale.ROOT);
        boolean[] used = new boolean[t.length()];
        List<Span> out = new ArrayList<>();
        for (Name n : names) {
            String key = n.name().toUpperCase(Locale.ROOT);
            int from = 0;
            while (true) {
                int at = upper.indexOf(key, from);
                if (at < 0) break;
                int end = at + key.length();
                from = at + 1;
                if (overlaps(used, at, end) || (isAscii(key) && !boundary(upper, at, end))) continue;
                if (isAscii(key) && at > 0 && upper.charAt(at - 1) == '%') continue;   // "5%LEL" 의 LEL 은 단위
                String rest = t.substring(end).stripLeading();
                if (dict.nameStopFollowers().stream().anyMatch(rest::startsWith)) continue;
                for (int i = at; i < end; i++) used[i] = true;
                out.add(new Span(at, end, n));
            }
        }
        out.sort(Comparator.comparingInt(Span::start));
        return out;
    }

    private static boolean overlaps(boolean[] used, int start, int end) {
        for (int i = start; i < end; i++) if (used[i]) return true;
        return false;
    }

    private static boolean isAscii(String s) {
        return s.chars().allMatch(c -> c < 128);
    }

    private static boolean boundary(String s, int start, int end) {
        boolean left = start == 0 || !Character.isLetterOrDigit(s.charAt(start - 1)) || s.charAt(start - 1) > 127;
        boolean right = end >= s.length() || !Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) > 127;
        return left && right;
    }

    private String unitOf(String after) {
        String a = after.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : unitWords) {
            if (a.startsWith(e.getKey())) return e.getValue();
        }
        return null;
    }

    private boolean startsWithUnit(String after) {
        return unitOf(after) != null;
    }

    private int unitLength(String after) {
        String a = after.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : unitWords) {
            if (a.startsWith(e.getKey())) return e.getKey().length();
        }
        return 0;
    }

    private static String unitLabel(String code) {
        return switch (code) {
            case "PCT" -> "%";
            case "PPM" -> "ppm";
            case "PCT_LEL" -> "%LEL";
            default -> code;
        };
    }

    // ------------------------------------------------------------------ 감시자·구역

    private String watcher(String t, List<String> notes) {
        String words = String.join("|", dict.watcherWords().stream().map(Pattern::quote).toList());
        Matcher m = Pattern.compile("(" + words + ")\\s*(?:은|는|이|가|:|으로|로)?\\s*([가-힣]{2,5})").matcher(t);
        while (m.find()) {
            String name = m.group(2);
            if (name.length() >= 3 && PARTICLES.indexOf(name.charAt(name.length() - 1)) >= 0) {
                name = name.substring(0, name.length() - 1);
            }
            if (name.endsWith("씨") || name.endsWith("님")) {
                name = name.substring(0, name.length() - 1);
            }
            String n = name;
            if (n.length() >= 2 && dict.notNames().stream().noneMatch(n::startsWith)) {
                return n;
            }
        }
        return null;
    }

    private static boolean mentions(String t, ZoneRef z) {
        String upper = t.toUpperCase(Locale.ROOT);
        return (z.code() != null && !z.code().isBlank() && upper.contains(z.code().toUpperCase(Locale.ROOT)))
                || (z.name() != null && z.name().length() >= 2 && t.contains(z.name()));
    }
}
