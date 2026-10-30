package com.example.backend.approval.service;

import com.example.backend.approval.dto.ApprovalDetail;
import com.example.backend.approval.dto.ApprovalSummary;
import com.example.backend.approval.entity.*;
import com.example.backend.approval.repository.ApprovalPolicyRepository;
import com.example.backend.approval.repository.ApprovalRepository;
import com.example.backend.approval.repository.ApprovalStepRepository;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.history.entity.ActorType;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.notification.entity.*;
import com.example.backend.notification.repository.NotificationRecipientRepository;
import com.example.backend.notification.repository.NotificationRepository;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMember;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.repository.SiteMemberRepository;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.site.service.SiteAccessService;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 결재 (계획서 7-5장). 문서가 승인 요청되면 결재선을 복사해 결재·단계를 만들고, 결정되면 문서 상태를 같은 트랜잭션에서 바꾼다.
 * <ul>
 *   <li>결재선: 현장 × 문서 종류 정책(approval_policies). 없으면 1단계 APPROVER</li>
 *   <li>결재 가능: 그 현장에서 단계의 required_role 이상 (작성자 &lt; 승인자 &lt; 현장 관리자 &lt; 회사 관리자), 지정자가 있으면 그 사람만</li>
 *   <li>자기 승인: 안내 모드(GUIDE) 허용, 검증 모드(VERIFY) 금지</li>
 *   <li>반려(보완 요청 포함): 사유 필수, 누락 항목은 이력에, 남은 단계 SKIPPED. 다시 요청하면 새 결재 1단계부터</li>
 *   <li>요청·승인·반려·회수는 이력과 알림(요청 → 그 단계 결재 가능자, 결과 → 요청자)을 남긴다</li>
 * </ul>
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final int INBOX_LIMIT = 200;
    private static final String DEFAULT_LABEL = "승인";

    private final ApprovalRepository approvalRepository;
    private final ApprovalStepRepository stepRepository;
    private final ApprovalPolicyRepository policyRepository;
    private final RecordHistoryRepository historyRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationRecipientRepository recipientRepository;
    private final SiteRepository siteRepository;
    private final SiteMemberRepository siteMemberRepository;
    private final SiteAccessService siteAccessService;
    private final SiteGuard siteGuard;
    private final UserRepository userRepository;
    private final Map<ApprovalTarget, ApprovalTargetHandler> handlers;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public ApprovalService(ApprovalRepository approvalRepository, ApprovalStepRepository stepRepository,
                           ApprovalPolicyRepository policyRepository, RecordHistoryRepository historyRepository,
                           NotificationRepository notificationRepository, NotificationRecipientRepository recipientRepository,
                           SiteRepository siteRepository, SiteMemberRepository siteMemberRepository,
                           SiteAccessService siteAccessService, SiteGuard siteGuard, UserRepository userRepository,
                           List<ApprovalTargetHandler> handlers, JsonMapper jsonMapper, Clock clock) {
        this.approvalRepository = approvalRepository;
        this.stepRepository = stepRepository;
        this.policyRepository = policyRepository;
        this.historyRepository = historyRepository;
        this.notificationRepository = notificationRepository;
        this.recipientRepository = recipientRepository;
        this.siteRepository = siteRepository;
        this.siteMemberRepository = siteMemberRepository;
        this.siteAccessService = siteAccessService;
        this.siteGuard = siteGuard;
        this.userRepository = userRepository;
        this.handlers = handlers.stream().collect(Collectors.toMap(ApprovalTargetHandler::type, Function.identity()));
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 승인 요청 (문서 서비스가 부름)

    /**
     * 승인 요청. 문서 서비스(PermitService.submit 등)가 문서를 PENDING 으로 바꾼 같은 트랜잭션에서 부른다.
     * 결재 마감 = 요청 시각 + 현장 설정 approval_due_minutes
     */
    @Transactional
    public Approval request(ApprovalTarget type, Long targetId, Long siteId, Long requesterId) {
        Site site = siteRepository.findById(siteId).orElseThrow(() -> new BusinessException(ErrorCode.SITE_NOT_FOUND));
        if (approvalRepository.findByTargetTypeAndTargetIdAndStatus(type, targetId, ApprovalStatus.PENDING).isPresent()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이미 승인 요청된 문서입니다.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime due = site.getApprovalDueMinutes() > 0 ? now.plusMinutes(site.getApprovalDueMinutes()) : null;
        Approval approval = approvalRepository.saveAndFlush(new Approval(siteId, type, targetId, requesterId, due, now));

        List<ApprovalPolicy> policies = policyRepository.findBySiteIdAndTargetTypeOrderByStepNoAsc(siteId, type);
        List<ApprovalStep> steps = new ArrayList<>();
        if (policies.isEmpty()) {
            steps.add(new ApprovalStep(approval, 1, DEFAULT_LABEL, ApproverRole.APPROVER, null));
        } else {
            for (int i = 0; i < policies.size(); i++) {
                ApprovalPolicy p = policies.get(i);
                steps.add(new ApprovalStep(approval, i + 1, p.getLabel(), p.getRequiredRole(), null));
            }
        }
        stepRepository.saveAll(steps);
        history(approval, HistoryAction.SUBMIT, requesterId, Map.of("approvalId", approval.getId(), "steps", steps.size()));
        notifyStep(approval, steps.get(0), site, now);
        return approval;
    }

    // ------------------------------------------------------------------ 결정

    /** 지금 단계 승인. 마지막 단계면 결재 승인 + 문서 승인, 아니면 다음 단계로 */
    @Transactional
    public ApprovalDetail approve(Long userId, Long approvalId, String comment) {
        Approval approval = lockPending(approvalId);
        Site site = site(approval);
        User user = siteGuard.requireAccess(userId, approval.getSiteId());
        ApprovalStep step = currentStep(approval);
        requireCanDecide(user, approval, step, site);
        ApprovalTargetHandler handler = handler(approval.getTargetType());
        Optional<ApprovalStep> next = stepRepository.findByApprovalIdAndStepNo(approval.getId(), step.getStepNo() + 1);
        if (next.isEmpty()) {
            handler.checkBeforeFinalApprove(approval.getTargetId());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        guard(() -> step.approve(userId, comment, now));
        stepRepository.saveAndFlush(step);   // 한 결재에 PENDING 단계는 하나: 지금 단계를 먼저 저장한 뒤 다음 단계를 연다

        if (next.isPresent()) {
            ApprovalStep n = next.get();
            n.open();
            approval.moveTo(n.getStepNo());
            history(approval, HistoryAction.APPROVE, userId, Map.of("approvalId", approval.getId(),
                    "step", step.getStepNo(), "final", false));
            notifyStep(approval, n, site, now);
        } else {
            guard(() -> approval.approve(userId, comment, now));
            guard(() -> handler.onApproved(approval.getTargetId()));
            history(approval, HistoryAction.APPROVE, userId, Map.of("approvalId", approval.getId(),
                    "step", step.getStepNo(), "final", true));
            notifyResult(approval, "승인되었습니다", now);
        }
        approvalRepository.saveAndFlush(approval);
        return detail(user, approval);
    }

    /** 반려 (보완 요청 포함). 남은 단계는 건너뛰고, 문서는 반려 상태로 */
    @Transactional
    public ApprovalDetail reject(Long userId, Long approvalId, String reason, List<String> missingItems) {
        Approval approval = lockPending(approvalId);
        Site site = site(approval);
        User user = siteGuard.requireAccess(userId, approval.getSiteId());
        ApprovalStep step = currentStep(approval);
        requireCanDecide(user, approval, step, site);
        LocalDateTime now = LocalDateTime.now(clock);
        guard(() -> step.reject(userId, reason, now));
        skipRemaining(approval, step.getStepNo());
        guard(() -> approval.reject(userId, reason, now));
        ApprovalTargetHandler handler = handler(approval.getTargetType());
        guard(() -> handler.onRejected(approval.getTargetId()));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("approvalId", approval.getId());
        after.put("step", step.getStepNo());
        after.put("reason", reason.trim());
        after.put("missingItems", missingItems == null ? List.of() : missingItems.stream()
                .filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList());
        history(approval, HistoryAction.REJECT, userId, after);
        notifyResult(approval, "반려되었습니다", now);
        approvalRepository.saveAndFlush(approval);
        return detail(user, approval);
    }

    /** 요청자가 결정 전에 회수. 문서는 다시 작성 중 */
    @Transactional
    public ApprovalDetail cancel(Long userId, Long approvalId, String comment) {
        Approval approval = lockPending(approvalId);
        User user = siteGuard.requireAccess(userId, approval.getSiteId());
        if (!approval.getRequestedBy().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "승인을 요청한 사람만 회수할 수 있습니다.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        skipRemaining(approval, 0);
        guard(() -> approval.cancel(comment, now));
        ApprovalTargetHandler handler = handler(approval.getTargetType());
        guard(() -> handler.onCanceled(approval.getTargetId()));
        history(approval, HistoryAction.CANCEL, userId, Map.of("approvalId", approval.getId()));
        approvalRepository.saveAndFlush(approval);
        return detail(user, approval);
    }

    // ------------------------------------------------------------------ 조회

    /** 내 승인 대기: 이 현장의 대기 결재 중 지금 단계를 내가 결재할 수 있는 것 (오래된 순) */
    @Transactional(readOnly = true)
    public List<ApprovalSummary> pending(Long userId, Long siteId) {
        User user = siteGuard.requireAccess(userId, siteId);
        Site site = siteRepository.findById(siteId).orElseThrow(() -> new BusinessException(ErrorCode.SITE_NOT_FOUND));
        List<Approval> approvals = approvalRepository.findBySiteIdAndStatusOrderByRequestedAtAscIdAsc(siteId,
                ApprovalStatus.PENDING, PageRequest.of(0, INBOX_LIMIT)).getContent();
        List<Approval> mine = approvals.stream()
                .filter(a -> canDecide(user, a, currentStep(a), site))
                .toList();
        return summaries(mine);
    }

    /** 내가 요청한 문서 (최근 순, 대기·승인·반려·회수 모두) */
    @Transactional(readOnly = true)
    public List<ApprovalSummary> requested(Long userId, Long siteId) {
        siteGuard.requireAccess(userId, siteId);
        return summaries(approvalRepository.findBySiteIdAndRequestedByOrderByRequestedAtDescIdDesc(siteId, userId,
                PageRequest.of(0, INBOX_LIMIT)).getContent());
    }

    @Transactional(readOnly = true)
    public ApprovalDetail get(Long userId, Long approvalId) {
        Approval approval = approvalRepository.findById(approvalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "결재를 찾을 수 없습니다."));
        User user = siteGuard.requireAccess(userId, approval.getSiteId());
        return detail(user, approval);
    }

    /** 결재 마감이 지난 대기 결재 (대시보드 승인 지연, 마감 순). 현장 권한 확인은 부르는 쪽에서 */
    @Transactional(readOnly = true)
    public List<ApprovalSummary> overdue(Long siteId, LocalDateTime now) {
        return summaries(approvalRepository.findBySiteIdAndStatusAndDueAtBeforeOrderByDueAtAscIdAsc(siteId,
                ApprovalStatus.PENDING, now));
    }

    /** 결재 목록을 요약으로 (기록 조회 도구). 현장 권한 확인은 부르는 쪽에서 */
    @Transactional(readOnly = true)
    public List<ApprovalSummary> describe(List<Approval> approvals) {
        return summaries(approvals);
    }

    // ------------------------------------------------------------------ 마감 재알림 (DeadlineNotifier 가 부름)

    /**
     * 결재 마감이 지난 대기 결재마다, 지금 단계를 결재할 수 있는 사람에게 한 번 재알림 (단계마다 한 번).
     * @return 새로 보낸 알림 수
     */
    @Transactional
    public int remindOverdue(LocalDateTime now) {
        int sent = 0;
        for (Approval a : approvalRepository.findByStatusAndDueAtBeforeOrderByDueAtAscIdAsc(ApprovalStatus.PENDING, now)) {
            String key = "DEADLINE:APPROVAL:" + a.getId() + ":" + a.getCurrentStep();
            ApprovalStep step = currentStep(a);
            if (step == null || notificationRepository.findByDedupeKey(key).isPresent()) {
                continue;
            }
            Site site = site(a);
            Set<Long> to = stepCandidates(a, step, site);
            if (to.isEmpty()) {
                continue;
            }
            String title = handler(a.getTargetType()).title(a.getTargetId());
            Notification n = notificationRepository.save(Notification.of(a.getSiteId(), NotificationType.DEADLINE,
                            NotificationSeverity.WARN, "승인 지연", cut(title + " · " + step.getLabel()
                                    + " 결재 마감이 지났습니다.", 500), now)
                    .about(NotificationTarget.APPROVAL, a.getId())
                    .dedupeKey(key));
            to.forEach(userId -> recipientRepository.save(new NotificationRecipient(n, userId)));
            sent++;
        }
        return sent;
    }

    // ------------------------------------------------------------------ 권한

    /** 현장 역할 서열: 작성자 0 < 승인자 1 < 현장 관리자 2 < 회사 관리자 3. 권한 없음 -1 */
    public static int rankOf(String siteRole) {
        if (siteRole == null) return -1;
        return switch (siteRole) {
            case "WRITER" -> 0;
            case "APPROVER" -> 1;
            case "SITE_ADMIN" -> 2;
            case "ADMIN" -> 3;
            default -> -1;
        };
    }

    public static int rankOf(ApproverRole required) {
        return rankOf(required.name());
    }

    private boolean canDecide(User user, Approval approval, ApprovalStep step, Site site) {
        try {
            requireCanDecide(user, approval, step, site);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    private void requireCanDecide(User user, Approval approval, ApprovalStep step, Site site) {
        if (step == null || step.getStatus() != ApprovalStepStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "지금 결재할 단계가 없습니다.");
        }
        int rank = rankOf(siteAccessService.roleAt(user, approval.getSiteId()).orElse(null));
        if (rank < rankOf(step.getRequiredRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "이 단계(" + step.getLabel() + ")를 결재할 권한이 없습니다.");
        }
        if (step.getAssigneeId() != null && !step.getAssigneeId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "이 단계는 지정된 결재자만 처리할 수 있습니다.");
        }
        if (approval.getRequestedBy().equals(user.getId()) && site.getMode() == SiteMode.VERIFY) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "검증 모드 현장에서는 자기 문서를 승인할 수 없습니다.");
        }
    }

    // ------------------------------------------------------------------ 내부

    private Approval lockPending(Long approvalId) {
        Approval approval = approvalRepository.findForUpdate(approvalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "결재를 찾을 수 없습니다."));
        if (!approval.isPending()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이미 결정된 결재입니다. 다시 요청하려면 문서를 고친 뒤 승인 요청하세요.");
        }
        return approval;
    }

    private ApprovalStep currentStep(Approval approval) {
        return stepRepository.findByApprovalIdAndStepNo(approval.getId(), approval.getCurrentStep()).orElse(null);
    }

    private void skipRemaining(Approval approval, int afterStepNo) {
        for (ApprovalStep s : stepRepository.findByApprovalIdOrderByStepNoAsc(approval.getId())) {
            if (s.getStepNo() > afterStepNo && !s.isDecided()) {
                s.skip();
            }
        }
    }

    private Site site(Approval approval) {
        return siteRepository.findById(approval.getSiteId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SITE_NOT_FOUND));
    }

    private ApprovalTargetHandler handler(ApprovalTarget type) {
        ApprovalTargetHandler h = handlers.get(type);
        if (h == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "아직 결재를 지원하지 않는 문서입니다: " + type);
        }
        return h;
    }

    /** 엔티티 검증 오류(입력값·상태)를 업무 오류로 바꾼다 (그대로 두면 500) */
    private static void guard(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_STATE, e.getMessage());
        }
    }

    private void history(Approval approval, HistoryAction action, Long userId, Map<String, Object> after) {
        historyRepository.save(RecordHistory.byUser(approval.getSiteId(), HistoryTarget.valueOf(approval.getTargetType().name()),
                approval.getTargetId(), action, userId, null, jsonMapper.writeValueAsString(after)));
    }

    // ------------------------------------------------------------------ 알림

    /** 그 단계를 결재할 수 있는 사람에게 승인 요청 알림. 검증 모드면 요청자는 빼고 */
    private void notifyStep(Approval approval, ApprovalStep step, Site site, LocalDateTime now) {
        Set<Long> to = stepCandidates(approval, step, site);
        if (to.isEmpty()) {
            log.warn("결재할 수 있는 현장 권한자가 없어 승인 요청 알림을 보내지 않았습니다. approvalId={} step={}",
                    approval.getId(), step.getStepNo());
            return;
        }
        String title = handler(approval.getTargetType()).title(approval.getTargetId());
        Notification n = notificationRepository.save(Notification.of(approval.getSiteId(), NotificationType.APPROVAL_REQUEST,
                        NotificationSeverity.WARN, "승인 요청", cut(title + " · " + step.getLabel() + " 차례입니다.", 500), now)
                .about(NotificationTarget.APPROVAL, approval.getId())
                .by(ActorType.USER, approval.getRequestedBy())
                .dedupeKey("APPROVAL_REQUEST:" + approval.getId() + ":" + step.getStepNo()));
        to.forEach(userId -> recipientRepository.save(new NotificationRecipient(n, userId)));
    }

    /** 그 단계를 결재할 수 있는 현장 인원: 지정자, 아니면 역할이 충분한 ACTIVE 인원. 검증 모드면 요청자 제외 */
    private Set<Long> stepCandidates(Approval approval, ApprovalStep step, Site site) {
        Set<Long> to = new LinkedHashSet<>();
        if (step.getAssigneeId() != null) {
            to.add(step.getAssigneeId());
        } else {
            int need = rankOf(step.getRequiredRole());
            for (SiteMember m : siteMemberRepository.findBySiteIdAndStatus(approval.getSiteId(), "ACTIVE")) {
                if (rankOf(m.getRole().name()) >= need) {
                    to.add(m.getUserId());
                }
            }
        }
        if (site.getMode() == SiteMode.VERIFY) {
            to.remove(approval.getRequestedBy());
        }
        return to;
    }

    /** 결과를 요청자에게 */
    private void notifyResult(Approval approval, String result, LocalDateTime now) {
        String title = handler(approval.getTargetType()).title(approval.getTargetId());
        Notification n = notificationRepository.save(Notification.of(approval.getSiteId(), NotificationType.APPROVAL_RESULT,
                        NotificationSeverity.INFO, "승인 결과", cut(title + " · " + result + ".", 500), now)
                .about(NotificationTarget.APPROVAL, approval.getId())
                .by(ActorType.USER, approval.getApproverId())
                .dedupeKey("APPROVAL_RESULT:" + approval.getId()));
        recipientRepository.save(new NotificationRecipient(n, approval.getRequestedBy()));
    }

    // ------------------------------------------------------------------ 응답

    private List<ApprovalSummary> summaries(List<Approval> approvals) {
        Set<Long> userIds = approvals.stream().map(Approval::getRequestedBy).collect(Collectors.toSet());
        Map<Long, String> names = names(userIds);
        LocalDateTime now = LocalDateTime.now(clock);
        return approvals.stream().map(a -> summary(a, names, now)).toList();
    }

    private ApprovalSummary summary(Approval a, Map<Long, String> names, LocalDateTime now) {
        List<ApprovalStep> steps = stepRepository.findByApprovalIdOrderByStepNoAsc(a.getId());
        String label = steps.stream().filter(s -> s.getStepNo() == a.getCurrentStep()).map(ApprovalStep::getLabel)
                .findFirst().orElse(null);
        ApprovalTargetHandler h = handlers.get(a.getTargetType());
        String title = h == null ? a.getTargetType() + " " + a.getTargetId() : h.title(a.getTargetId());
        boolean self = a.getApproverId() != null && a.getApproverId().equals(a.getRequestedBy());
        return new ApprovalSummary(a.getId(), a.getTargetType(), a.getTargetId(), title, a.getRequestedBy(),
                names.get(a.getRequestedBy()), a.getRequestedAt(), a.getDueAt(), a.isOverdue(now), a.getStatus(),
                a.getCurrentStep(), steps.size(), label, a.getComment(), self);
    }

    private ApprovalDetail detail(User me, Approval a) {
        List<ApprovalStep> steps = stepRepository.findByApprovalIdOrderByStepNoAsc(a.getId());
        Set<Long> userIds = new HashSet<>();
        userIds.add(a.getRequestedBy());
        steps.stream().map(ApprovalStep::getDecidedBy).filter(Objects::nonNull).forEach(userIds::add);
        Map<Long, String> names = names(userIds);
        LocalDateTime now = LocalDateTime.now(clock);

        List<ApprovalDetail.Step> stepViews = steps.stream().map(s -> new ApprovalDetail.Step(s.getStepNo(), s.getLabel(),
                s.getRequiredRole(), s.getAssigneeId(), s.getStatus(), s.getDecidedBy(),
                s.getDecidedBy() == null ? null : names.get(s.getDecidedBy()), s.getDecidedAt(), s.getComment())).toList();
        boolean canDecide = a.isPending() && canDecide(me, a, currentStep(a), site(a));
        boolean canCancel = a.isPending() && a.getRequestedBy().equals(me.getId());
        return new ApprovalDetail(summary(a, names, now), stepViews, missingItems(a), canDecide, canCancel);
    }

    /** 반려 때 고른 누락 항목 (반려 이력에서) */
    private List<String> missingItems(Approval a) {
        if (a.getStatus() != ApprovalStatus.REJECTED) {
            return List.of();
        }
        List<RecordHistory> rows = historyRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAscIdAsc(
                HistoryTarget.valueOf(a.getTargetType().name()), a.getTargetId());
        for (int i = rows.size() - 1; i >= 0; i--) {
            RecordHistory h = rows.get(i);
            if (h.getAction() == HistoryAction.REJECT && h.getAfterJson() != null) {
                JsonNode after = jsonMapper.readTree(h.getAfterJson());
                if (after.path("approvalId").asLong() == a.getId()) {
                    List<String> items = new ArrayList<>();
                    after.path("missingItems").forEach(n -> items.add(n.asString()));
                    return items;
                }
            }
        }
        return List.of();
    }

    private Map<Long, String> names(Collection<Long> userIds) {
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u.getName() == null ? u.getEmail() : u.getName(), (x, y) -> x));
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
