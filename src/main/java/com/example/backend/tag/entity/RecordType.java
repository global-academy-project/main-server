package com.example.backend.tag.entity;

/** 태그를 붙이는 기록 종류 (DB ck_tag_record). RISK_ITEM 은 risk_assessment_items 한 줄 */
public enum RecordType {
    NEAR_MISS, WORK, PERMIT, INSPECTION, RISK_ITEM
}
