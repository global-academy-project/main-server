package com.example.backend.site.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.site.dto.AdminSiteRequest;
import com.example.backend.site.dto.SiteSettingsRequest;
import com.example.backend.site.dto.SiteSettingsResponse;
import com.example.backend.site.entity.Site;
import com.example.backend.site.repository.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 현장 정보·운영 설정.
 * <ul>
 *   <li>조회는 현장 권한, 운영 설정(마감 시각·재알림·경고 기간·업종 등)은 현장 관리자 이상</li>
 *   <li>현장 등록·이름·모드·사용 중지는 회사 관리자 (/api/v1/admin/sites, SecurityConfig 에서 ADMIN 만)</li>
 *   <li>바꾸면 이력(SITE, UPDATE)에 바뀐 뒤 값을 남긴다</li>
 * </ul>
 */
@Service
public class SiteSettingsService {

    private final SiteRepository siteRepository;
    private final RecordHistoryRepository historyRepository;
    private final SiteGuard siteGuard;
    private final JsonMapper jsonMapper;

    public SiteSettingsService(SiteRepository siteRepository, RecordHistoryRepository historyRepository,
                               SiteGuard siteGuard, JsonMapper jsonMapper) {
        this.siteRepository = siteRepository;
        this.historyRepository = historyRepository;
        this.siteGuard = siteGuard;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(readOnly = true)
    public SiteSettingsResponse get(Long userId, Long siteId) {
        siteGuard.requireAccess(userId, siteId);
        return SiteSettingsResponse.from(site(siteId));
    }

    @Transactional
    public SiteSettingsResponse update(Long userId, Long siteId, SiteSettingsRequest r) {
        siteGuard.requireSiteAdmin(userId, siteId);
        Site site = site(siteId);
        guard(() -> {
            site.updateSettings(r.tbmDeadline(), r.dailyLogDeadline(), r.approvalDueMinutes(), r.nearMissWarnDays());
            site.updateProfile(blank(r.industry()), r.employeeCount(), blank(r.address()));
        });
        siteRepository.saveAndFlush(site);
        history(site, HistoryAction.UPDATE, userId);
        return SiteSettingsResponse.from(site);
    }

    // ------------------------------------------------------------------ 회사 관리자

    /** 전체 현장 (사용 중지 포함, 이름 순) */
    @Transactional(readOnly = true)
    public List<SiteSettingsResponse> listAll() {
        return siteRepository.findAllByOrderByNameAsc().stream().map(SiteSettingsResponse::from).toList();
    }

    @Transactional
    public SiteSettingsResponse create(Long adminId, AdminSiteRequest r) {
        String name = r.name().trim();
        if (siteRepository.existsByName(name)) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "같은 이름의 현장이 있습니다: " + name);
        }
        Site site = siteRepository.saveAndFlush(new Site(name, r.mode()));
        history(site, HistoryAction.CREATE, adminId);
        return SiteSettingsResponse.from(site);
    }

    @Transactional
    public SiteSettingsResponse updateByAdmin(Long adminId, Long siteId, AdminSiteRequest r) {
        Site site = site(siteId);
        String name = r.name().trim();
        if (!name.equals(site.getName()) && siteRepository.existsByName(name)) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "같은 이름의 현장이 있습니다: " + name);
        }
        guard(() -> site.rename(name));
        if (r.mode() != null) site.changeMode(r.mode());
        if (Boolean.FALSE.equals(r.active())) site.deactivate();
        if (Boolean.TRUE.equals(r.active())) site.activate();
        siteRepository.saveAndFlush(site);
        history(site, HistoryAction.UPDATE, adminId);
        return SiteSettingsResponse.from(site);
    }

    // ------------------------------------------------------------------ 내부

    private Site site(Long siteId) {
        return siteRepository.findById(siteId).orElseThrow(() -> new BusinessException(ErrorCode.SITE_NOT_FOUND));
    }

    private void history(Site s, HistoryAction action, Long userId) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", s.getName());
        after.put("mode", s.getMode());
        after.put("tbmDeadline", s.getTbmDeadline().toString());
        after.put("dailyLogDeadline", s.getDailyLogDeadline().toString());
        after.put("approvalDueMinutes", s.getApprovalDueMinutes());
        after.put("nearMissWarnDays", s.getNearMissWarnDays());
        after.put("active", s.isActive());
        historyRepository.save(RecordHistory.byUser(s.getId(), HistoryTarget.SITE, s.getId(), action, userId, null,
                jsonMapper.writeValueAsString(after)));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void guard(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, e.getMessage());
        }
    }
}
