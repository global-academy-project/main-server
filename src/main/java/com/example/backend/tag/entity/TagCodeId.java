package com.example.backend.tag.entity;

import java.io.Serializable;
import java.util.Objects;

/** tag_codes 복합키 (axis, code) */
public class TagCodeId implements Serializable {

    private TagAxis axis;
    private String code;

    protected TagCodeId() {
    }

    public TagCodeId(TagAxis axis, String code) {
        this.axis = axis;
        this.code = code;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TagCodeId that)) return false;
        return axis == that.axis && Objects.equals(code, that.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(axis, code);
    }
}
