package com.example.backend.nearmiss.dto;

import com.example.backend.nearmiss.entity.NearMissStatus;
import com.example.backend.tag.entity.TagAxis;
import com.example.backend.tag.entity.TagSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 아차사고 조회. tags[].label 은 코드표 이름 (물질 CAS 는 null) */
public record NearMissResponse(Long nearMissId, Long siteId, Long zoneId, String zoneName, Long workId, String title,
                               LocalDateTime occurredAt, String description, String immediateAction, NearMissStatus status,
                               Long reporterId, String reporterName, LocalDateTime createdAt, int lockVersion,
                               List<Tag> tags) {

    public record Tag(TagAxis axis, String value, String label, TagSource source, BigDecimal confidence) {
    }
}
