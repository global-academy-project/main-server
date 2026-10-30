package com.example.backend.tbm.dto;

import com.example.backend.tbm.entity.TbmStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * TBM 조회 (에이전트 초안 포함).
 *  - points: 교육 요점 (저장 형식은 JSON 배열 문자열, 배열이 아니면 원문 한 줄)
 *  - works: 이 TBM 에 묶인 작업, attendees: 참석 작업자 (이름은 작업자 명부에서)
 */
public record TbmResponse(Long tbmId, Long siteId, LocalDate tbmDate, LocalDateTime heldAt, String leaderName,
                          Integer attendeeCount, List<String> points, String healthCheck, TbmStatus status,
                          Long writerId, Long agentRunId, int lockVersion, List<WorkRef> works,
                          List<Attendee> attendees) {

    public record WorkRef(Long workId, String title) {
    }

    public record Attendee(Long workerId, String name, Boolean healthOk) {
    }
}
