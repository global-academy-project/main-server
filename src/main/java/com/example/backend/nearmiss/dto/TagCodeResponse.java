package com.example.backend.nearmiss.dto;

import com.example.backend.tag.entity.TagAxis;

/** 태그 코드표 한 줄 (저장에는 code 를 value 로) */
public record TagCodeResponse(TagAxis axis, String code, String label) {
}
