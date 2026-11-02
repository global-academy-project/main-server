package com.example.backend.tbm.dto;

import com.example.backend.tbm.dto.SaveTbmRequest.Attendee;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** TBM 참석자만 통째로 바꾸기 (PUT /api/v1/tbm/{id}/attendees). 그 현장에 배치된 작업자만. lockVersion 필수 */
public record SaveTbmAttendeesRequest(@NotNull Integer lockVersion,
                                      @NotNull @Size(max = 200) List<@Valid @NotNull Attendee> attendees) {
}
