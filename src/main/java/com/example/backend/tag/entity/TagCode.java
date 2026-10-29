package com.example.backend.tag.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.regex.Pattern;

/**
 * 5축 분류 코드표. 테이블 규칙은 V5 tag_codes (초기 값도 V5에 있음).
 * record_tags·risk_assessment_items.accident_type 은 이 표에 있는 코드만 쓸 수 있다 (DB 외래키).
 * 코드는 지우지 않고 사용 중지(active = false) → 과거 태그가 그대로 남는다.
 */
@Entity
@Table(name = "tag_codes")
@IdClass(TagCodeId.class)
public class TagCode {

    /** 대문자·숫자·밑줄, 대문자로 시작 (DB ck_tc_code) */
    public static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    @Id
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20, updatable = false)
    private TagAxis axis;

    @Id
    @Column(nullable = false, length = 30, updatable = false)
    private String code;

    @Column(nullable = false, length = 100)
    private String label;

    /** 상위 코드 (같은 축, 선택) */
    @Column(length = 30)
    private String parent;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    protected TagCode() {
    }

    public TagCode(TagAxis axis, String code, String label, String parent, int sortOrder) {
        if (axis == null || !axis.isCoded()) {
            throw new IllegalArgumentException("코드표 축은 SEVERITY·ACCIDENT_TYPE·HAZARD·WORK_TYPE 입니다.");
        }
        if (code == null || code.length() > 30 || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("코드는 대문자·숫자·밑줄 30자 이내입니다 (예: FIRE).");
        }
        this.axis = axis;
        this.code = code;
        update(label, parent, sortOrder);
    }

    public void update(String label, String parent, int sortOrder) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("표시 이름이 필요합니다.");
        }
        this.label = label.trim();
        this.parent = parent;
        this.sortOrder = sortOrder;
    }

    public void deactivate() { this.active = false; }

    public void activate() { this.active = true; }

    public TagAxis getAxis() { return axis; }
    public String getCode() { return code; }
    public String getLabel() { return label; }
    public String getParent() { return parent; }
    public int getSortOrder() { return sortOrder; }
    public boolean isActive() { return active; }
}
