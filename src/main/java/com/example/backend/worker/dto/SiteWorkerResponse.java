package com.example.backend.worker.dto;

import com.example.backend.worker.entity.EmploymentType;

import java.time.LocalDate;

/** 현장에 배치된 작업자 한 명 (TBM 참석자 선택 등). 연락처는 개인정보라 내보내지 않는다. lockVersion 은 수정할 때 */
public record SiteWorkerResponse(Long workerId, String name, String jobType, Long teamId, String teamName,
                                 EmploymentType employmentType, LocalDate assignedFrom, LocalDate assignedTo,
                                 int lockVersion) {
}
