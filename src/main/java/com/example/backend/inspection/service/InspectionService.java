package com.example.backend.inspection.service;

import com.example.backend.approval.entity.Approval;
import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.repository.ApprovalRepository;
import com.example.backend.approval.service.ApprovalService;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.jpa.LockVersions;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.inspection.dto.CreateInspectionRequest;
import com.example.backend.inspection.dto.InspectionResponse;
import com.example.backend.inspection.dto.SaveInspectionRequest;
import com.example.backend.inspection.entity.Inspection;
import com.example.backend.inspection.entity.InspectionItem;
import com.example.backend.inspection.entity.InspectionResult;
import com.example.backend.inspection.entity.InspectionStatus;
import com.example.backend.inspection.entity.InspectionTarget;
import com.example.backend.inspection.repository.InspectionItemRepository;
import com.example.backend.inspection.repository.InspectionRepository;
import com.example.backend.site.entity.Equipment;
import com.example.backend.site.entity.WorkZone;
import com.example.backend.site.repository.EquipmentRepository;
import com.example.backend.site.repository.WorkZoneRepository;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.work.repository.WorkRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 장비·구역 점검표 (계획서 7-5장: DRAFT → PENDING(결과 필수) → APPROVED, 반려되면 같은 기록을 작성 중으로).
 * <ul>
 *   <li>만들 때 양식(InspectionTemplates)의 항목을 복사한다</li>
 *   <li>조회·작성·제출은 현장 권한. 같은 대상·날짜·양식 점검표는 하나 (반려 후 되돌린 것 포함)</li>
 *   <li>제출하면 종합 결과를 확정하고 승인 요청(결재선은 현장 정책, 없으면 1단계)</li>
 * </ul>
 */
@Service
public class InspectionService {

    private final InspectionRepository inspectionRepository;
    private final InspectionItemRepository itemRepository;
    private final WorkZoneRepository zoneRepository;
    private final EquipmentRepository equipmentRepository;
    private final WorkRepository workRepository;
    private final UserRepository userRepository;
    private final ApprovalRepository approvalRepository;
    private final ApprovalService approvalService;
    private final RecordHistoryRepository historyRepository;
    private final InspectionTemplates templates;
    private final LockVersions lockVersions;
    private final SiteGuard siteGuard;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final int listMaxDays;

    public InspectionService(InspectionRepository inspectionRepository, InspectionItemRepository itemRepository,
                             WorkZoneRepository zoneRepository, EquipmentRepository equipmentRepository,
                             WorkRepository workRepository, UserRepository userRepository,
                             ApprovalRepository approvalRepository, ApprovalService approvalService,
                             RecordHistoryRepository historyRepository, InspectionTemplates templates,
                             LockVersions lockVersions, SiteGuard siteGuard, JsonMapper jsonMapper, Clock clock,
                             @Value("${app.inspection.list.max-days}") int listMaxDays) {
        this.inspectionRepository = inspectionRepository;
        this.itemRepository = itemRepository;
        this.zoneRepository = zoneRepository;
        this.equipmentRepository = equipmentRepository;
        this.workRepository = workRepository;
        this.userRepository = userRepository;
        this.approvalRepository = approvalRepository;
        this.approvalService = approvalService;
        this.historyRepository = historyRepository;
        this.templates = templates;
        this.lockVersions = lockVersions;
        this.siteGuard = siteGuard;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.listMaxDays = listMaxDays;
    }

    public List<InspectionTemplates.Template> templates() {
        return templates.all();
    }

    @Transactional
    public InspectionResponse create(Long userId, Long siteId, CreateInspectionRequest r) {
        User user = siteGuard.requireAccess(userId, siteId);
        InspectionTemplates.Template template = templates.find(r.templateCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "없는 점검표 양식입니다: " + r.templateCode()));
        if (template.targetType() != r.targetType()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "양식 " + template.code() + " 은 " + template.targetType()
                    + " 점검용입니다.");
        }
        Inspection inspection;
        if (r.targetType() == InspectionTarget.ZONE) {
            if (r.zoneId() == null || r.equipmentId() != null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "구역 점검은 zoneId 만 넣어 주세요.");
            }
            activeZone(siteId, r.zoneId());
            inspection = Inspection.forZone(siteId, r.zoneId(), r.inspectDate(), template.code(), user.getId());
        } else {
            if (r.equipmentId() == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "장비 점검은 equipmentId 가 필요합니다.");
            }
            Equipment e = equipmentRepository.findByIdAndSiteId(r.equipmentId(), siteId).filter(Equipment::isActive)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "이 현장의 사용 중인 설비가 아닙니다: "
                            + r.equipmentId()));
            Long zoneId = r.zoneId() != null ? r.zoneId() : e.getZoneId();
            if (r.zoneId() != null) {
                activeZone(siteId, r.zoneId());
            }
            inspection = Inspection.forEquipment(siteId, e.getId(), zoneId, r.inspectDate(), template.code(), user.getId());
        }
        if (r.workId() != null) {
            workRepository.findById(r.workId()).filter(w -> w.getSiteId().equals(siteId))
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "이 현장의 작업이 아닙니다: " + r.workId()));
            inspection.linkWork(r.workId());
        }
        Inspection probe = inspection;
        boolean exists = inspectionRepository.findBySiteIdAndInspectDateOrderByIdAsc(siteId, r.inspectDate()).stream()
                .anyMatch(i -> i.getTemplateCode().equals(probe.getTemplateCode()) && i.getTargetType() == probe.getTargetType()
                        && Objects.equals(i.getZoneId(), probe.getZoneId())
                        && Objects.equals(i.getEquipmentId(), probe.getEquipmentId()));
        if (exists) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "같은 날짜·대상·양식의 점검표가 이미 있습니다. 그 점검표를 이어서 작성해 주세요.");
        }
        Inspection saved = inspectionRepository.saveAndFlush(inspection);
        List<InspectionItem> items = new ArrayList<>();
        int seq = 1;
        for (InspectionTemplates.Item i : template.items()) {
            items.add(new InspectionItem(saved.getId(), seq++, i.itemCode(), i.question()));
        }
        itemRepository.saveAll(items);
        history(saved, HistoryAction.CREATE, user.getId());
        return response(saved);
    }

    @Transactional(readOnly = true)
    public InspectionResponse get(Long userId, Long inspectionId) {
        return response(load(userId, inspectionId));
    }

    /** 현장의 기간 점검표 (날짜·id 순). 비우면 오늘 하루. 기간은 yaml app.inspection.list.max-days 일까지 */
    @Transactional(readOnly = true)
    public List<InspectionResponse> list(Long userId, Long siteId, LocalDate from, LocalDate to) {
        siteGuard.requireAccess(userId, siteId);
        LocalDate today = LocalDate.now(clock);
        LocalDate start = from != null ? from : (to != null ? to : today);
        LocalDate end = to != null ? to : start;
        if (end.isBefore(start)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "to 는 from 과 같거나 뒤여야 합니다.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > listMaxDays) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "점검표 목록은 한 번에 " + listMaxDays + "일까지 볼 수 있습니다.");
        }
        return inspectionRepository.findBySiteIdAndInspectDateBetweenOrderByInspectDateAscIdAsc(siteId, start, end).stream()
                .map(this::response).toList();
    }

    @Transactional
    public InspectionResponse save(Long userId, Long inspectionId, SaveInspectionRequest r) {
        Inspection inspection = loadEditable(userId, inspectionId);
        if (r.lockVersion() != inspection.getLockVersion()) {
            throw new BusinessException(ErrorCode.CONFLICT_MODIFIED);
        }
        Map<String, InspectionItem> items = itemRepository.findByInspectionIdOrderBySeqAsc(inspectionId).stream()
                .collect(Collectors.toMap(InspectionItem::getItemCode, Function.identity()));
        Set<String> seen = new HashSet<>();
        for (SaveInspectionRequest.Answer a : r.items()) {
            String code = a.itemCode().trim();
            if (!seen.add(code)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 항목이 두 번 있습니다: " + code);
            }
            InspectionItem item = items.get(code);
            if (item == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "이 점검표에 없는 항목입니다: " + code);
            }
            String note = a.note() == null || a.note().isBlank() ? null : a.note().trim();
            try {
                item.record(a.result(), note);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, code + ": " + e.getMessage());
            }
        }
        itemRepository.saveAll(items.values());
        if (r.inspectDate() != null && !r.inspectDate().equals(inspection.getInspectDate())) {
            guard(() -> inspection.updateContent(r.inspectDate(), inspection.getFormData()));
            inspectionRepository.saveAndFlush(inspection);
        }
        lockVersions.bumpOnce(inspection, inspection.getId(), r.lockVersion(), inspection::getLockVersion);
        history(inspection, HistoryAction.UPDATE, userId);
        return response(inspection);
    }

    /** 제출 = 종합 결과 확정 + 승인 요청. 모든 항목에 결과가 있어야 한다 */
    @Transactional
    public InspectionResponse submit(Long userId, Long inspectionId) {
        Inspection inspection = loadEditable(userId, inspectionId);
        InspectionResult result = InspectionResult.of(itemRepository.findByInspectionIdOrderBySeqAsc(inspectionId).stream()
                .map(InspectionItem::getResult).toList());
        if (result == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "모든 항목의 결과를 적어야 제출할 수 있습니다.");
        }
        guard(() -> inspection.submit(result));
        inspectionRepository.saveAndFlush(inspection);
        approvalService.request(ApprovalTarget.INSPECTION, inspectionId, inspection.getSiteId(), userId);
        history(inspection, HistoryAction.SUBMIT, userId);
        return response(inspection);
    }

    // ------------------------------------------------------------------ 내부

    private Inspection load(Long userId, Long inspectionId) {
        Inspection inspection = inspectionRepository.findById(inspectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "점검표를 찾을 수 없습니다."));
        siteGuard.requireAccess(userId, inspection.getSiteId());
        return inspection;
    }

    private Inspection loadEditable(Long userId, Long inspectionId) {
        Inspection inspection = load(userId, inspectionId);
        if (inspection.getStatus() != InspectionStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_STATE, inspection.getStatus() == InspectionStatus.PENDING
                    ? "승인 요청 중인 점검표입니다. 회수한 뒤 고쳐 주세요."
                    : "작성 중인 점검표만 고칠 수 있습니다 (현재 " + inspection.getStatus() + ").");
        }
        return inspection;
    }

    private void activeZone(Long siteId, Long zoneId) {
        zoneRepository.findByIdAndSiteId(zoneId, siteId).filter(WorkZone::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "이 현장의 사용 중인 구역이 아닙니다: " + zoneId));
    }

    private InspectionResponse response(Inspection i) {
        List<InspectionItem> items = itemRepository.findByInspectionIdOrderBySeqAsc(i.getId());
        String zoneName = i.getZoneId() == null ? null : zoneRepository.findById(i.getZoneId()).map(WorkZone::getName).orElse(null);
        String equipmentName = i.getEquipmentId() == null ? null
                : equipmentRepository.findById(i.getEquipmentId()).map(Equipment::getName).orElse(null);
        String templateName = templates.find(i.getTemplateCode()).map(InspectionTemplates.Template::name).orElse(null);
        String inspectorName = userRepository.findById(i.getInspectorId()).map(User::getName).orElse(null);
        Approval approval = approvalRepository
                .findFirstByTargetTypeAndTargetIdOrderByRequestedAtDescIdDesc(ApprovalTarget.INSPECTION, i.getId()).orElse(null);
        InspectionResult expected = InspectionResult.of(items.stream().map(InspectionItem::getResult).toList());
        int answered = (int) items.stream().filter(x -> x.getResult() != null).count();
        List<InspectionResponse.Item> rows = items.stream().map(x -> {
            InspectionTemplates.ItemInfo info = templates.info(i.getTemplateCode(), x.getItemCode());
            return new InspectionResponse.Item(x.getSeq(), x.getItemCode(), info == null ? null : info.section(),
                    x.getQuestion(), info == null ? null : info.detail(), x.getResult(), x.getNote());
        }).toList();
        return new InspectionResponse(i.getId(), i.getSiteId(), i.getTargetType(), i.getZoneId(), zoneName, i.getEquipmentId(),
                equipmentName, i.getWorkId(), i.getInspectDate(), i.getTemplateCode(), templateName, i.getResult(), expected,
                i.getStatus(), i.getInspectorId(), inspectorName, answered, items.size(),
                i.getStatus() == InspectionStatus.DRAFT && expected != null, i.getLockVersion(),
                approval == null ? null : approval.getId(), approval == null ? null : approval.getStatus(), rows);
    }

    private void history(Inspection i, HistoryAction action, Long userId) {
        historyRepository.save(RecordHistory.byUser(i.getSiteId(), HistoryTarget.INSPECTION, i.getId(), action, userId, null,
                jsonMapper.writeValueAsString(Map.of("templateCode", i.getTemplateCode(),
                        "inspectDate", i.getInspectDate().toString(), "status", i.getStatus(),
                        "result", i.getResult() == null ? "" : i.getResult().name()))));
    }

    private static void guard(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_STATE, e.getMessage());
        }
    }
}
