package com.example.backend.work.entity;

import com.example.backend.rule.entity.WorkType;

import java.io.Serializable;
import java.util.Objects;

/** work_risk_factors 복합키 (work_id, factor) */
public class WorkRiskFactorId implements Serializable {

    private Long workId;
    private WorkType factor;

    protected WorkRiskFactorId() {
    }

    public WorkRiskFactorId(Long workId, WorkType factor) {
        this.workId = workId;
        this.factor = factor;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkRiskFactorId that)) return false;
        return Objects.equals(workId, that.workId) && factor == that.factor;
    }

    @Override
    public int hashCode() {
        return Objects.hash(workId, factor);
    }
}
