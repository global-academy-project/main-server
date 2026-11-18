package com.example.backend.corrective.service;

import com.example.backend.audit.repository.AuditReportRepository;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.corrective.dto.AssigneeResponse;
import com.example.backend.corrective.dto.CorrectiveActionResponse;
import com.example.backend.corrective.dto.CorrectiveActionStepRequest;
import com.example.backend.corrective.dto.SaveCorrectiveActionRequest;
import com.example.backend.corrective.entity.ActionSource;
import com.example.backend.corrective.entity.ActionStatus;
import com.example.backend.corrective.entity.CorrectiveAction;
import com.example.backend.corrective.repository.CorrectiveActionRepository;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.inspection.repository.InspectionRepository;
import com.example.backend.nearmiss.repository.NearMissRepository;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMember;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.repository.SiteMemberRepository;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.site.service.SiteAccessService;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserRole;
import com.example.backend.user.entity.UserStatus;
import com.example.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 개선조치: 등록 → 시작 → 완료(조치 내용) → 확인. 확인에서 미흡하면 다시 조치 중, 끝나기 전에는 취소.
 * <ul>
 *   <li>출처: 아차사고·점검표·감사보고서(같은 현장 기록) 또는 기타. 규칙 위반 출처는 만드는 화면이 없어 받지 않는다</li>
 *   <li>담당자: 이 현장에 접근할 수 있는 사용 중 계정</li>
 *   <li>등록·수정·시작·완료: 그 현장 인원. 확인·미흡·취소: 승인자 이상(승인자·현장 관리자·회사 관리자)</li>
 *   <li>확인: 검증 모드(VERIFY)에서는 담당자 본인이 확인할 수 없다. 안내 모드(GUIDE)는 허용 (1인 업체)</li>
 *   <li>모든 변경은 lockVersion 이 맞아야 하고, record_history 에 남긴다</li>
 * </ul>
 */
@Service
public class CorrectiveActionService {

    private static final Set<String> VERIFIER_ROLES = Set.of("APPROVER", "SITE_ADMIN", "ADMIN");
    private static final Set<ActionSource> CREATABLE = EnumSet.of(ActionSource.NEAR_MISS, ActionSource.INSPECTION,
            ActionSource.AUDIT, ActionSource.OTHER);

    private final CorrectiveActionRepository actionRepository;
    private final NearMissRepository nearMissRepository;
    private final InspectionRepository inspectionRepository;
    private final AuditReportRepository auditReportRepository;
    private final SiteRepository siteRepository;
    private final SiteMemberRepository siteMemberRepository;
    private final UserRepository userRepository;
    private final RecordHistoryRepository historyRepository;
    private final SiteGuard siteGuard;
    private final SiteAccessService siteAccessService;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final EntityManager em;

    public CorrectiveActionService(CorrectiveActionRepository actionRepository, NearMissRepository nearMissRepository,
                                   InspectionRepository inspectionRepository, AuditReportRepository auditReportRepository,
                                   SiteRepository siteRepository, SiteMemberRepository siteMemberRepository,
                                   UserRepository userRepository, RecordHistoryRepository historyRepository,
                                   SiteGuard siteGuard, SiteAccessService siteAccessService, JsonMapper jsonMapper,
                                   Clock clock, EntityManager em) {
        this.actionRepository = actionRepository;
        this.nearMissRepository = nearMissRepository;
        this.inspectionRepository = inspectionRepository;
        this.auditReportRepository = auditReportRepository;
        this.siteRepository = siteRepository;
        this.siteMemberRepository = siteMemberRepository;
        this.userRepository = userRepository;
        this.historyRepository = historyRepository;
        this.siteGuard = siteGuard;
        this.siteAccessService = siteAccessService;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.em = em;
    }

    // ------------------------------------------------------------------ 조회

    /** 현장의 개선조치 (기한 순). status·sourceType·sourceId 로 거른다 (비우면 전체) */
    @Transactional(readOnly = true)
    public List<CorrectiveActionResponse> list(Long userId, Long siteId, List<ActionStatus> statuses,
                                               ActionSource sourceType, Long sourceId) {
        siteGuard.requireAccess(userId, siteId);
        List<CorrectiveAction> actions = actionRepository.findBySiteIdOrderByDueDateAscIdAsc(siteId).stream()
                .filter(a -> statuses == null || statuses.isEmpty() || statuses.contains(a.getStatus()))
                .filter(a -> sourceType == null || a.getSourceType() == sourceType)
                .filter(a -> sourceId == null || sourceId.equals(a.getSourceId()))
                .toList();
        return responses(actions);
    }

    @Transactional(readOnly = true)
    public CorrectiveActionResponse get(Long userId, Long actionId) {
        CorrectiveAction a = load(actionId);
        siteGuard.requireAccess(userId, a.getSiteId());
        return responses(List.of(a)).get(0);
    }

    /** 담당자 후보: 이 현장의 사용 중 현장 인원 + 사용 중 회사 관리자 (이름 순) */
    @Transactional(readOnly = true)
    public List<AssigneeResponse> assignees(Long userId, Long siteId) {
        siteGuard.requireAccess(userId, siteId);
        Map<Long, String> roles = new LinkedHashMap<>();
        for (SiteMember m : siteMemberRepository.findBySiteIdAndStatus(siteId, "ACTIVE")) {
            roles.put(m.getUserId(), m.getRole().name());
        }
        List<User> users = new ArrayList<>(userRepository.findAllById(roles.keySet()));
        userRepository.findByStatusOrderByCreatedAtDesc(UserStatus.ACTIVE).stream()
                .filter(u -> u.getRole() == UserRole.ADMIN && !roles.containsKey(u.getId()))
                .forEach(u -> {
                    roles.put(u.getId(), "ADMIN");
                    users.add(u);
                });
        return users.stream().filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .map(u -> new AssigneeResponse(u.getId(), u.getName(), u.getRole() == UserRole.ADMIN ? "ADMIN" : roles.get(u.getId())))
                .sorted(Comparator.comparing(AssigneeResponse::name, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    // ------------------------------------------------------------------ 등록·수정

    @Transactional
    public CorrectiveActionResponse create(Long userId, Long siteId, SaveCorrectiveActionRequest r) {
        siteGuard.requireAccess(userId, siteId);
        ActionSource source = r.sourceType() == null ? ActionSource.OTHER : r.sourceType();
        Long sourceId = source == ActionSource.OTHER ? null : r.sourceId();
        checkSource(siteId, source, sourceId);
        checkAssignee(siteId, r.assigneeId());
        CorrectiveAction a = guard(() -> new CorrectiveAction(siteId, source, sourceId, r.title(), trim(r.description()),
                r.assigneeId(), r.dueDate(), userId));
        a = actionRepository.saveAndFlush(a);
        em.refresh(a);   // 등록 시각(created_at)은 DB 기본값
        history(a, HistoryAction.CREATE, userId, null);
        return get(userId, a.getId());
    }

    @Transactional
    public CorrectiveActionResponse update(Long userId, Long actionId, SaveCorrectiveActionRequest r) {
        CorrectiveAction a = loadForChange(userId, actionId, r.lockVersion());
        checkAssignee(a.getSiteId(), r.assigneeId());
        run(() -> a.update(r.title(), trim(r.description()), r.assigneeId(), r.dueDate()));
        actionRepository.saveAndFlush(a);
        history(a, HistoryAction.UPDATE, userId, null);
        return get(userId, actionId);
    }

    // ------------------------------------------------------------------ 상태

    @Transactional
    public CorrectiveActionResponse start(Long userId, Long actionId, CorrectiveActionStepRequest r) {
        CorrectiveAction a = loadForChange(userId, actionId, r.lockVersion());
        run(a::start);
        return saved(a, HistoryAction.STATUS_CHANGE, userId, null);
    }

    @Transactional
    public CorrectiveActionResponse complete(Long userId, Long actionId, CorrectiveActionStepRequest r) {
        CorrectiveAction a = loadForChange(userId, actionId, r.lockVersion());
        run(() -> a.complete(LocalDateTime.now(clock), r.note()));
        return saved(a, HistoryAction.STATUS_CHANGE, userId, null);
    }

    /** 확인 (승인자 이상). 검증 모드에서는 담당자 본인 확인 금지 */
    @Transactional
    public CorrectiveActionResponse verify(Long userId, Long actionId, CorrectiveActionStepRequest r) {
        CorrectiveAction a = loadForChange(userId, actionId, r.lockVersion());
        requireVerifier(userId, a);
        Site site = siteRepository.findById(a.getSiteId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (site.getMode() == SiteMode.VERIFY && userId.equals(a.getAssigneeId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "검증 모드 현장에서는 담당자 본인이 확인할 수 없습니다.");
        }
        run(() -> a.verify(userId, LocalDateTime.now(clock)));
        return saved(a, HistoryAction.VERIFY, userId, null);
    }

    /** 확인 단계에서 미흡 → 다시 조치 중 (승인자 이상, 사유 기록) */
    @Transactional
    public CorrectiveActionResponse reopen(Long userId, Long actionId, CorrectiveActionStepRequest r) {
        CorrectiveAction a = loadForChange(userId, actionId, r.lockVersion());
        requireVerifier(userId, a);
        run(a::reopen);
        return saved(a, HistoryAction.STATUS_CHANGE, userId, trim(r.note()));
    }

    /** 취소 (승인자 이상 또는 등록한 사람, 끝나기 전만) */
    @Transactional
    public CorrectiveActionResponse cancel(Long userId, Long actionId, CorrectiveActionStepRequest r) {
        CorrectiveAction a = loadForChange(userId, actionId, r.lockVersion());
        if (!userId.equals(a.getCreatedBy())) {
            requireVerifier(userId, a);
        }
        run(a::cancel);
        return saved(a, HistoryAction.CANCEL, userId, trim(r.note()));
    }

    // ------------------------------------------------------------------ 내부

    private CorrectiveAction load(Long actionId) {
        return actionRepository.findById(actionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "개선조치를 찾을 수 없습니다."));
    }

    private CorrectiveAction loadForChange(Long userId, Long actionId, Integer lockVersion) {
        CorrectiveAction a = load(actionId);
        siteGuard.requireAccess(userId, a.getSiteId());
        if (lockVersion == null || lockVersion != a.getLockVersion()) {
            throw new BusinessException(ErrorCode.CONFLICT_MODIFIED);
        }
        return a;
    }

    private CorrectiveActionResponse saved(CorrectiveAction a, HistoryAction action, Long userId, String reason) {
        actionRepository.saveAndFlush(a);
        history(a, action, userId, reason);
        return get(userId, a.getId());
    }

    private void requireVerifier(Long userId, CorrectiveAction a) {
        User user = siteGuard.requireAccess(userId, a.getSiteId());
        String role = siteAccessService.roleAt(user, a.getSiteId()).orElse(null);
        if (!VERIFIER_ROLES.contains(role)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "승인자 이상만 확인·미흡 처리·취소할 수 있습니다.");
        }
    }

    /** 출처는 같은 현장의 기록이어야 한다 */
    private void checkSource(Long siteId, ActionSource source, Long sourceId) {
        if (!CREATABLE.contains(source)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이 출처로는 개선조치를 만들 수 없습니다: " + source);
        }
        if (source == ActionSource.OTHER) {
            return;
        }
        if (sourceId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "출처 기록 번호가 필요합니다.");
        }
        Optional<Long> site = switch (source) {
            case NEAR_MISS -> nearMissRepository.findById(sourceId).map(n -> n.getSiteId());
            case INSPECTION -> inspectionRepository.findById(sourceId).map(i -> i.getSiteId());
            case AUDIT -> auditReportRepository.findById(sourceId).map(r -> r.getSiteId());
            default -> Optional.empty();
        };
        if (site.isEmpty() || !site.get().equals(siteId)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이 현장의 기록이 아닙니다: " + source + " " + sourceId);
        }
    }

    private void checkAssignee(Long siteId, Long assigneeId) {
        User assignee = assigneeId == null ? null : userRepository.findById(assigneeId).orElse(null);
        if (assignee == null || assignee.getStatus() != UserStatus.ACTIVE || !siteAccessService.canAccess(assignee, siteId)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이 현장의 사용 중인 인원만 담당자로 정할 수 있습니다.");
        }
    }

    private List<CorrectiveActionResponse> responses(List<CorrectiveAction> actions) {
        Set<Long> ids = new HashSet<>();
        actions.forEach(a -> {
            ids.add(a.getAssigneeId());
            ids.add(a.getCreatedBy());
            if (a.getVerifiedBy() != null) {
                ids.add(a.getVerifiedBy());
            }
        });
        Map<Long, String> names = userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, u -> u.getName() == null ? "" : u.getName(), (x, y) -> x));
        LocalDate today = LocalDate.now(clock);
        return actions.stream().map(a -> new CorrectiveActionResponse(a.getId(), a.getSiteId(), a.getSourceType(),
                a.getSourceId(), a.getTitle(), a.getDescription(), a.getAssigneeId(), names.get(a.getAssigneeId()),
                a.getDueDate(), a.getStatus(), a.isOverdue(today), a.getCompletedAt(), a.getCompletionNote(),
                a.getVerifiedBy(), a.getVerifiedBy() == null ? null : names.get(a.getVerifiedBy()), a.getVerifiedAt(),
                a.getCreatedBy(), names.get(a.getCreatedBy()), a.getCreatedAt(), a.getLockVersion())).toList();
    }

    private void history(CorrectiveAction a, HistoryAction action, Long userId, String reason) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", a.getStatus().name());
        after.put("assigneeId", a.getAssigneeId());
        after.put("dueDate", a.getDueDate().toString());
        if (reason != null) {
            after.put("reason", reason);
        }
        historyRepository.save(RecordHistory.byUser(a.getSiteId(), HistoryTarget.CORRECTIVE_ACTION, a.getId(), action,
                userId, null, jsonMapper.writeValueAsString(after)));
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static <T> T guard(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, e.getMessage());
        }
    }

    private static void run(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_STATE, e.getMessage());
        }
    }
}
