package com.example.backend.common.util;

/**
 * JSON 컬럼에 넣을 문자열 최소 확인. 엔티티는 문자열로 받고(직렬화는 서비스 담당),
 * DB CHECK(JSON_TYPE = 'OBJECT')에 걸리기 전에 객체 모양인지만 먼저 본다.
 */
public final class JsonText {

    private JsonText() {
    }

    /** null·빈 값이면 null, 있으면 { } 객체 모양이어야 한다 */
    public static String objectOrNull(String json, String what) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return object(json, what);
    }

    /** 필수 JSON 객체 */
    public static String object(String json, String what) {
        if (json == null) {
            throw new IllegalArgumentException(what + "이(가) 필요합니다.");
        }
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new IllegalArgumentException(what + "은(는) JSON 객체여야 합니다.");
        }
        return trimmed;
    }
}
