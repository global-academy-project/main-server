package com.example.backend.worker.dto;

import com.example.backend.worker.entity.TeamType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 팀·협력업체 등록·수정 (회사 관리자). 이름·사업자등록번호는 겹칠 수 없다. 사업자등록번호는 외부 업체만, 숫자 10자리 */
public record TeamRequest(@NotBlank @Size(max = 100) String name, TeamType teamType, @Size(max = 12) String businessNo,
                          Boolean active) {
}
