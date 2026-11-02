package com.example.backend.site.dto;

import com.example.backend.site.entity.SiteMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 현장 등록·수정 (회사 관리자).
 *  - mode: VERIFY(검증, 기본) / GUIDE(안내). 자기 승인 규칙이 바뀌므로 회사 관리자만 바꾼다
 *  - active: 수정 때만. false 면 사용 중지 (기록은 남음)
 */
public record AdminSiteRequest(@NotBlank @Size(max = 100) String name, SiteMode mode, Boolean active) {
}
