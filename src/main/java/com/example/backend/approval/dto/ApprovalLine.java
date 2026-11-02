package com.example.backend.approval.dto;

import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.entity.ApproverRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 문서 종류별 결재선. defaultLine=true 면 현장 정책이 없어 기본 1단계(승인자)를 쓴다.
 * 단계 번호는 순서대로 1부터.
 */
public record ApprovalLine(ApprovalTarget targetType, boolean defaultLine, List<Step> steps) {

    public record Step(int stepNo, String label, ApproverRole requiredRole) {
    }

    /** PUT 본문 한 단계 (순서는 목록 순서) */
    public record StepRequest(@NotBlank @Size(max = 50) String label, @NotNull ApproverRole requiredRole) {
    }
}
