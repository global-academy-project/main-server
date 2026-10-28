package com.example.backend.rule.service;

import com.example.backend.rule.entity.Rule;
import com.example.backend.rule.entity.RuleSeverity;

/**
 * 어긋난 규칙 하나.
 * actual 은 입력값 (없으면 null), expected 는 사람이 읽는 기준 (예: "18 이상 23.5 미만 (%)").
 * message·action 은 보통 규칙 문구 그대로이고, 값이 없을 때만 "측정값이 없습니다." 로 바뀐다.
 */
public record Finding(Rule rule, String actual, String expected, String message, String action) {

    public RuleSeverity severity() {
        return rule.getSeverity();
    }
}
