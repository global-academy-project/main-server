package com.example.backend.worker.dto;

import com.example.backend.worker.entity.EmploymentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 현장 작업자 등록(새 작업자 + 이 현장 배치)·수정.
 *  - assignedFrom: 등록 때 배치일 (비우면 오늘)
 *  - phone: 하이픈은 빼고 저장. 응답에는 내보내지 않는다 (개인정보). 수정 때 null 이면 그대로, "" 이면 지움
 *  - lockVersion: 수정 때 필수
 */
public record SiteWorkerRequest(@NotBlank @Size(max = 50) String name, @Size(max = 20) String phone,
                                @Size(max = 50) String jobType, EmploymentType employmentType, Long teamId,
                                LocalDate assignedFrom, Integer lockVersion) {
}
