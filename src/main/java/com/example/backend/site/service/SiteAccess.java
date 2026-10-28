package com.example.backend.site.service;

/**
 * 내가 볼 수 있는 현장 하나 (대시보드 드롭다운 항목).
 * role: WRITER | APPROVER | SITE_ADMIN, 회사 관리자(users.role=ADMIN)는 모든 현장에 ADMIN
 */
public record SiteAccess(Long siteId, String name, String role) {
}
