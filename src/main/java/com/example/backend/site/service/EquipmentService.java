package com.example.backend.site.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.site.dto.EquipmentRequest;
import com.example.backend.site.dto.EquipmentResponse;
import com.example.backend.site.entity.Equipment;
import com.example.backend.site.repository.EquipmentRepository;
import com.example.backend.site.repository.WorkZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 설비(장비 점검 대상): 조회는 현장 권한, 등록·수정은 현장 관리자 이상. 지우지 않고 사용 중지 */
@Service
public class EquipmentService {

    private static final Pattern TYPE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    private final EquipmentRepository equipmentRepository;
    private final WorkZoneRepository zoneRepository;
    private final RecordHistoryRepository historyRepository;
    private final SiteGuard siteGuard;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public EquipmentService(EquipmentRepository equipmentRepository, WorkZoneRepository zoneRepository,
                            RecordHistoryRepository historyRepository, SiteGuard siteGuard, JsonMapper jsonMapper,
                            Clock clock) {
        this.equipmentRepository = equipmentRepository;
        this.zoneRepository = zoneRepository;
        this.historyRepository = historyRepository;
        this.siteGuard = siteGuard;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** 사용 중인 설비 (코드 순). includeInactive=true 면 사용 중지 설비도 */
    @Transactional(readOnly = true)
    public List<EquipmentResponse> list(Long userId, Long siteId, boolean includeInactive) {
        siteGuard.requireAccess(userId, siteId);
        LocalDate today = LocalDate.now(clock);
        List<Equipment> rows = includeInactive
                ? equipmentRepository.findBySiteIdOrderByCodeAsc(siteId)
                : equipmentRepository.findBySiteIdAndActiveTrueOrderByCodeAsc(siteId);
        return rows.stream().map(e -> EquipmentResponse.from(e, today)).toList();
    }

    @Transactional
    public EquipmentResponse create(Long userId, Long siteId, EquipmentRequest r) {
        siteGuard.requireSiteAdmin(userId, siteId);
        String code = r.code().trim();
        if (equipmentRepository.existsBySiteIdAndCode(siteId, code)) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이 현장에 같은 설비 코드가 있습니다: " + code);
        }
        check(siteId, r);
        Equipment e = guard(() -> new Equipment(siteId, r.zoneId(), code, r.name(), r.equipmentType().trim(),
                r.nextInspectionDate()));
        equipmentRepository.saveAndFlush(e);
        history(e, HistoryAction.CREATE, userId);
        return EquipmentResponse.from(e, LocalDate.now(clock));
    }

    @Transactional
    public EquipmentResponse update(Long userId, Long siteId, Long equipmentId, EquipmentRequest r) {
        siteGuard.requireSiteAdmin(userId, siteId);
        Equipment e = equipmentRepository.findByIdAndSiteId(equipmentId, siteId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "설비를 찾을 수 없습니다."));
        String code = r.code().trim();
        if (!code.equals(e.getCode()) && equipmentRepository.existsBySiteIdAndCode(siteId, code)) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이 현장에 같은 설비 코드가 있습니다: " + code);
        }
        check(siteId, r);
        guard(() -> {
            e.update(r.zoneId(), code, r.name(), r.equipmentType().trim(), r.nextInspectionDate());
            return e;
        });
        if (Boolean.FALSE.equals(r.active())) e.deactivate();
        if (Boolean.TRUE.equals(r.active())) e.activate();
        equipmentRepository.saveAndFlush(e);
        history(e, HistoryAction.UPDATE, userId);
        return EquipmentResponse.from(e, LocalDate.now(clock));
    }

    private void check(Long siteId, EquipmentRequest r) {
        if (!TYPE.matcher(r.equipmentType().trim()).matches()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "설비 종류는 대문자·숫자·_ 로 적어 주세요 (예: GAS_DETECTOR).");
        }
        if (r.zoneId() != null && zoneRepository.findByIdAndSiteId(r.zoneId(), siteId).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이 현장의 구역이 아닙니다: " + r.zoneId());
        }
    }

    private void history(Equipment e, HistoryAction action, Long userId) {
        historyRepository.save(RecordHistory.byUser(e.getSiteId(), HistoryTarget.EQUIPMENT, e.getId(), action, userId, null,
                jsonMapper.writeValueAsString(Map.of("code", e.getCode(), "name", e.getName(), "active", e.isActive()))));
    }

    private static <T> T guard(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, e.getMessage());
        }
    }
}
