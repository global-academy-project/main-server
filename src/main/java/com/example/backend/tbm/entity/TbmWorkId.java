package com.example.backend.tbm.entity;

import java.io.Serializable;
import java.util.Objects;

/** tbm_works 복합키 (tbm_id, work_id) */
public class TbmWorkId implements Serializable {

    private Long tbmId;
    private Long workId;

    protected TbmWorkId() {
    }

    public TbmWorkId(Long tbmId, Long workId) {
        this.tbmId = tbmId;
        this.workId = workId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TbmWorkId that)) return false;
        return Objects.equals(tbmId, that.tbmId) && Objects.equals(workId, that.workId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tbmId, workId);
    }
}
