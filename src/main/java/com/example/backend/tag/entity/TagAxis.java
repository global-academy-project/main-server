package com.example.backend.tag.entity;

/**
 * 5축 분류 (DB ck_tag_axis). 검색 필터·Qdrant payload 와 같은 값이다.
 * SEVERITY 심각도 / ACCIDENT_TYPE 발생 형태 / HAZARD 유해·위험 요인 / WORK_TYPE 작업 종류 → tag_codes 코드만
 * SUBSTANCE 물질 → 값은 CAS 번호 (코드표 없음)
 */
public enum TagAxis {
    SEVERITY, ACCIDENT_TYPE, HAZARD, WORK_TYPE, SUBSTANCE;

    /** 코드표(tag_codes)에 있는 값만 쓰는 축인가 */
    public boolean isCoded() {
        return this != SUBSTANCE;
    }
}
