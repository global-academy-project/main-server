package com.example.backend.permit.entity;

import com.example.backend.rule.entity.WorkType;

/** 허가 종류 (DB ck_permits_type). 규칙 판정의 작업 종류와 이름이 같다 */
public enum PermitType {
    HOT, CONFINED, HEIGHT;

    public WorkType toWorkType() {
        return WorkType.valueOf(name());
    }
}
