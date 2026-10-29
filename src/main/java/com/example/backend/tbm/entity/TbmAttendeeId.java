package com.example.backend.tbm.entity;

import java.io.Serializable;
import java.util.Objects;

/** tbm_attendees 복합키 (tbm_id, worker_id) */
public class TbmAttendeeId implements Serializable {

    private Long tbmId;
    private Long workerId;

    protected TbmAttendeeId() {
    }

    public TbmAttendeeId(Long tbmId, Long workerId) {
        this.tbmId = tbmId;
        this.workerId = workerId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TbmAttendeeId that)) return false;
        return Objects.equals(tbmId, that.tbmId) && Objects.equals(workerId, that.workerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tbmId, workerId);
    }
}
