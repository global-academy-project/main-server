package com.example.backend.rule.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.rule.entity.EvaluationResult;
import com.example.backend.rule.entity.Rule;
import com.example.backend.rule.entity.RuleOperator;
import com.example.backend.rule.entity.RuleSeverity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 규칙 판정기. DB·LLM 없이 규칙 목록과 입력만으로 판정한다 → 같은 입력이면 항상 같은 결과.
 *
 * 입력 모양: { "measurement": { "O2": 17.5, ... }, "form": { "watcher_name": "..." } }
 * 규칙의 target(예: measurement.O2)을 점(.)으로 나눠 입력에서 값을 찾는다.
 *
 * 판정 규칙
 *  - 수치 규칙(RANGE, GTE, GT, LTE, LT, EQ)인데 값이 없으면 위반으로 본다 (값을 빼먹어서 통과되지 않게)
 *  - 수치 규칙에 숫자가 아닌 값(문자열 "17.5" 포함)이 오면 INVALID_REQUEST
 *  - REQUIRED: null, 빈 문자열, 빈 목록이면 위반
 *  - IN: allowed 목록 중 하나여야 함 (값이 없으면 위반). 예/아니오 확인 항목은 allowed = [true]
 *  - 결과는 위반 중 가장 무거운 것: BLOCK > WARN > (없으면) PASS
 */
@Component
public class RuleEvaluator {

    private static final Object MISSING = new Object();

    private final JsonMapper jsonMapper;

    public RuleEvaluator(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public Judgement evaluate(List<Rule> rules, Map<String, Object> input) {
        Map<String, Object> safeInput = input == null ? Map.of() : input;
        List<Finding> findings = new ArrayList<>();
        Set<String> usedPaths = new HashSet<>();

        for (Rule rule : rules) {
            usedPaths.add(rule.getTarget());
            Finding finding = check(rule, lookup(safeInput, rule.getTarget()));
            if (finding != null) {
                findings.add(finding);
            }
        }

        findings.sort(Comparator
                .comparing((Finding f) -> f.severity() == RuleSeverity.BLOCK ? 0 : 1)
                .thenComparing(f -> f.rule().getRuleCode()));

        Set<String> unused = new TreeSet<>();
        collectLeafPaths(safeInput, "", unused);
        unused.removeAll(usedPaths);

        return new Judgement(resultOf(findings), rules.size(), List.copyOf(findings), List.copyOf(unused));
    }

    // ------------------------------------------------------------------ 규칙 하나

    private Finding check(Rule rule, Object raw) {
        RuleOperator op = rule.getOperator();
        String expected = expectedText(rule);

        if (op == RuleOperator.REQUIRED) {
            return isBlank(raw) ? violation(rule, null, expected) : null;
        }
        if (op == RuleOperator.IN) {
            if (isBlank(raw)) {
                return violation(rule, null, expected);   // 체크 항목이 비었으면 규칙 문구 그대로 (근거 조항과 함께)
            }
            String actual = text(raw);
            boolean ok = allowedValues(rule).stream().anyMatch(a -> sameValue(text(a), actual));
            return ok ? null : violation(rule, actual, expected);
        }

        // 수치 규칙
        if (isBlank(raw)) {
            return missing(rule, expected);
        }
        BigDecimal v = number(rule.getTarget(), raw);
        boolean ok = switch (op) {
            case RANGE -> aboveMin(v, rule.getMinValue(), rule.isMinInclusive())
                    && belowMax(v, rule.getMaxValue(), rule.isMaxInclusive());
            case GTE -> v.compareTo(rule.getMinValue()) >= 0;
            case GT -> v.compareTo(rule.getMinValue()) > 0;
            case LTE -> v.compareTo(rule.getMaxValue()) <= 0;
            case LT -> v.compareTo(rule.getMaxValue()) < 0;
            case EQ -> v.compareTo(rule.getMinValue()) == 0;
            default -> throw new IllegalStateException("처리하지 않은 연산자: " + op);
        };
        return ok ? null : violation(rule, plain(v), expected);
    }

    private static boolean aboveMin(BigDecimal v, BigDecimal min, boolean inclusive) {
        int c = v.compareTo(min);
        return inclusive ? c >= 0 : c > 0;
    }

    private static boolean belowMax(BigDecimal v, BigDecimal max, boolean inclusive) {
        int c = v.compareTo(max);
        return inclusive ? c <= 0 : c < 0;
    }

    private static Finding violation(Rule rule, String actual, String expected) {
        return new Finding(rule, actual, expected, rule.getMessage(), rule.getAction());
    }

    /** 값이 없음. 측정 규칙이면 "측정값이 없습니다." */
    private static Finding missing(Rule rule, String expected) {
        boolean measurement = "MEASUREMENT".equals(rule.getCategory());
        return new Finding(rule, null, expected,
                measurement ? "측정값이 없습니다." : "값이 입력되지 않았습니다.",
                measurement ? "측정 후 입력" : "값 입력");
    }

    private static EvaluationResult resultOf(List<Finding> findings) {
        if (findings.stream().anyMatch(f -> f.severity() == RuleSeverity.BLOCK)) return EvaluationResult.BLOCK;
        if (!findings.isEmpty()) return EvaluationResult.WARN;
        return EvaluationResult.PASS;
    }

    // ------------------------------------------------------------------ 기준 문구

    /** 사람이 읽는 기준. 예: "18 이상 23.5 미만 (%)", "10 미만 (ppm)", "입력 필요", "A, B 중 하나" */
    String expectedText(Rule rule) {
        String text = switch (rule.getOperator()) {
            case RANGE -> plain(rule.getMinValue()) + (rule.isMinInclusive() ? " 이상 " : " 초과 ")
                    + plain(rule.getMaxValue()) + (rule.isMaxInclusive() ? " 이하" : " 미만");
            case GTE -> plain(rule.getMinValue()) + " 이상";
            case GT -> plain(rule.getMinValue()) + " 초과";
            case LTE -> plain(rule.getMaxValue()) + " 이하";
            case LT -> plain(rule.getMaxValue()) + " 미만";
            case EQ -> plain(rule.getMinValue());
            case IN -> inText(allowedValues(rule));
            case REQUIRED -> "입력 필요";
        };
        // 단위는 수치 기준에만 붙인다 ("입력 필요 (%LEL)" 처럼 어색해지지 않게)
        boolean numeric = rule.getOperator() != RuleOperator.REQUIRED && rule.getOperator() != RuleOperator.IN;
        String unit = numeric ? unitLabel(rule.getUnit()) : null;
        return unit == null ? text : text + " (" + unit + ")";
    }

    /** 예/아니오 확인 항목([true])은 "확인 필요", 그 밖에는 "A, B 중 하나" */
    private static String inText(List<Object> allowed) {
        if (allowed.size() == 1 && Boolean.TRUE.equals(allowed.get(0))) {
            return "확인 필요";
        }
        return String.join(", ", allowed.stream().map(RuleEvaluator::text).toList()) + " 중 하나";
    }

    private static String unitLabel(String unit) {
        if (unit == null) return null;
        return switch (unit) {
            case "PCT" -> "%";
            case "PPM" -> "ppm";
            case "PCT_LEL" -> "%LEL";
            case "M" -> "m";
            case "HOUR" -> "시간";
            case "PERSON" -> "명";
            default -> unit;
        };
    }

    // ------------------------------------------------------------------ 값 다루기

    /** "measurement.O2" → input.measurement.O2. 중간이 객체가 아니거나 키가 없으면 MISSING */
    @SuppressWarnings("unchecked")
    private static Object lookup(Map<String, Object> input, String path) {
        Object current = input;
        for (String key : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(key)) {
                return MISSING;
            }
            current = ((Map<String, Object>) map).get(key);
        }
        return current;
    }

    private static boolean isBlank(Object raw) {
        if (raw == MISSING || raw == null) return true;
        if (raw instanceof String s) return s.isBlank();
        if (raw instanceof Collection<?> c) return c.isEmpty();
        if (raw instanceof Map<?, ?> m) return m.isEmpty();
        return false;
    }

    /** 숫자만 받는다. 문자열 "17.5", true 등은 INVALID_REQUEST */
    private static BigDecimal number(String path, Object raw) {
        if (raw instanceof BigDecimal d) return d;
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            return BigDecimal.valueOf(((Number) raw).longValue());
        }
        if (raw instanceof Number n) {
            try {
                return new BigDecimal(n.toString());   // 17.5(double) → 17.5 그대로 (이진 오차 없이)
            } catch (NumberFormatException e) {
                // NaN, Infinity
            }
        }
        throw new BusinessException(ErrorCode.INVALID_REQUEST, path + " 값은 숫자여야 합니다.");
    }

    private List<Object> allowedValues(Rule rule) {
        if (rule.getAllowed() == null) return List.of();
        List<?> values = jsonMapper.readValue(rule.getAllowed(), List.class);
        return new ArrayList<>(values);
    }

    /** 숫자면 비교를 숫자로 (5 와 5.0 은 같음), 아니면 글자 그대로 */
    private static boolean sameValue(String a, String b) {
        try {
            return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0;
        } catch (NumberFormatException e) {
            return a.equals(b);
        }
    }

    private static String text(Object value) {
        if (value instanceof BigDecimal d) return plain(d);
        if (value instanceof Double || value instanceof Float) {
            try {
                return plain(new BigDecimal(value.toString()));
            } catch (NumberFormatException e) {
                return value.toString();
            }
        }
        return String.valueOf(value);
    }

    /** 18.000 → "18", 23.500 → "23.5" */
    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    /** 입력의 끝값 경로를 모은다. 객체는 안으로 들어가고, 그 밖의 값(숫자·문자·목록)은 끝값 */
    private static void collectLeafPaths(Map<?, ?> map, String prefix, Set<String> out) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String path = prefix + e.getKey();
            if (e.getValue() instanceof Map<?, ?> child) {
                collectLeafPaths(child, path + ".", out);   // 빈 객체({})는 알릴 값이 없으므로 건너뜀
            } else {
                out.add(path);
            }
        }
    }
}
