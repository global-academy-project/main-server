package com.example.backend.tbm.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * TBM 작성·수정. 수정은 내용·작업·참석자를 통째로 바꾼다.
 *  - workIds: 이 TBM 에서 다룬 작업. 같은 현장, 작업일 = TBM 날짜, 작업 하나는 하루에 TBM 하나 (DB uk_tw_work_date)
 *  - attendees: 그 현장에 배치된 작업자만 (worker_sites)
 *  - lockVersion: 수정할 때 필수. 다르면 409 CONFLICT_MODIFIED
 */
public record SaveTbmRequest(
        @NotNull LocalDate tbmDate,
        Long teamId,
        Long zoneId,
        @Size(max = 50) String leaderName,
        @Size(max = 30) List<@NotBlank @Size(max = 300) String> points,
        @Size(max = 500) String healthCheck,
        @Size(max = 50) List<@NotNull Long> workIds,
        @Size(max = 200) List<@Valid @NotNull Attendee> attendees,
        Integer lockVersion
) {

    public record Attendee(@NotNull Long workerId, Boolean healthOk) {
    }
}
