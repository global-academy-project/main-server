package com.example.backend.common.util;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** DB 의 LocalDateTime 을 명세의 ISO 8601 KST(+09:00) 로 변환한다. */
public final class DateTimes {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private DateTimes() {
    }

    public static OffsetDateTime toKst(LocalDateTime value) {
        return value == null ? null : value.atZone(KST).toOffsetDateTime();
    }
}
