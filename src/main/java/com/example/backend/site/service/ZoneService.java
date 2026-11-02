package com.example.backend.site.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.site.dto.ZoneRequest;
import com.example.backend.site.dto.ZoneResponse;
import com.example.backend.site.entity.WorkZone;
import com.example.backend.site.repository.WorkZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/** 작업구역: 조회는 현장 권한, 등록·수정은 현장 관리자 이상. 구역은 지우지 않고 사용 중지(active=false) */
@Service
public class ZoneService {

    private final WorkZoneRepository zoneRepository;
    private final RecordHistoryRepository historyRepository;
    private final SiteGuard siteGuard;
    private final JsonMapper jsonMapper;

    public ZoneService(WorkZoneRepository zoneRepository, RecordHistoryRepository historyRepository, SiteGuard siteGuard,
                       JsonMapper jsonMapper) {
        this.zoneRepository = zoneRepository;
        this.historyRepository = historyRepository;
        this.siteGuard = siteGuard;
        this.jsonMapper = jsonMapper;
    }

    /** 사용 중인 구역 (코드 순). includeInactive=true 면 사용 중지 구역도 */
    @Transactional(readOnly = true)
    public List<ZoneResponse> list(Long userId, Long siteId, boolean includeInactive) {
        siteGuard.requireAccess(userId, siteId);
        List<WorkZone> zones = includeInactive
                ? zoneRepository.findBySiteIdOrderByCodeAsc(siteId)
                : zoneRepository.findBySiteIdAndActiveTrueOrderByCodeAsc(siteId);
        return zones.stream().map(ZoneResponse::from).toList();
    }

    @Transactional
    public ZoneResponse create(Long userId, Long siteId, ZoneRequest r) {
        siteGuard.requireSiteAdmin(userId, siteId);
        String code = r.code().trim();
        if (zoneRepository.existsBySiteIdAndCode(siteId, code)) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이 현장에 같은 구역 코드가 있습니다: " + code);
        }
        WorkZone zone = guard(() -> new WorkZone(siteId, code, r.name(), blank(r.zoneType()), r.confined(), blank(r.hazardNote())));
        zoneRepository.saveAndFlush(zone);
        history(zone, HistoryAction.CREATE, userId);
        return ZoneResponse.from(zone);
    }

    @Transactional
    public ZoneResponse update(Long userId, Long siteId, Long zoneId, ZoneRequest r) {
        siteGuard.requireSiteAdmin(userId, siteId);
        WorkZone zone = zoneRepository.findByIdAndSiteId(zoneId, siteId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "구역을 찾을 수 없습니다."));
        String code = r.code().trim();
        if (!code.equals(zone.getCode()) && zoneRepository.existsBySiteIdAndCode(siteId, code)) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이 현장에 같은 구역 코드가 있습니다: " + code);
        }
        guard(() -> {
            zone.update(code, r.name(), blank(r.zoneType()), r.confined(), blank(r.hazardNote()));
            return zone;
        });
        if (Boolean.FALSE.equals(r.active())) zone.deactivate();
        if (Boolean.TRUE.equals(r.active())) zone.activate();
        zoneRepository.saveAndFlush(zone);
        history(zone, HistoryAction.UPDATE, userId);
        return ZoneResponse.from(zone);
    }

    private void history(WorkZone zone, HistoryAction action, Long userId) {
        historyRepository.save(RecordHistory.byUser(zone.getSiteId(), HistoryTarget.ZONE, zone.getId(), action, userId, null,
                jsonMapper.writeValueAsString(Map.of("code", zone.getCode(), "name", zone.getName(), "active", zone.isActive()))));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static <T> T guard(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, e.getMessage());
        }
    }
}
