package com.example.backend.dashboard.service;

import com.example.backend.approval.entity.ApproverRole;
import com.example.backend.approval.service.ApprovalService;
import com.example.backend.dashboard.dto.DashboardResponse.Entry;
import com.example.backend.dashboard.dto.IncompleteType;
import com.example.backend.notification.entity.Notification;
import com.example.backend.notification.entity.NotificationRecipient;
import com.example.backend.notification.entity.NotificationSeverity;
import com.example.backend.notification.entity.NotificationTarget;
import com.example.backend.notification.entity.NotificationType;
import com.example.backend.notification.repository.NotificationRecipientRepository;
import com.example.backend.notification.repository.NotificationRepository;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMember;
import com.example.backend.site.repository.SiteMemberRepository;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.work.entity.Work;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 마감 알림 (계획서 7-5장). 현장 설정 마감이 지났는데 끝나지 않은 것을 한 번씩 알린다 (중복 방지 키).
 * <ul>
 *   <li>TBM 미실시: tbm_deadline 이후, 승인자 이상 + 그 작업 작성자. 키 DEADLINE:TBM:{현장}:{날짜}</li>
 *   <li>안전일지 미작성: daily_log_deadline 이후, 승인자 이상. 키 DEADLINE:DAILY_LOG:{현장}:{날짜}</li>
 *   <li>개선조치 기한 지남: 담당자(엔티티에서 필수), 하루 한 번. 키 ACTION_DUE:{조치}:{날짜}</li>
 *   <li>승인 지연: ApprovalService.remindOverdue (단계마다 한 번)</li>
 * </ul>
 * TBM·안전일지 알림은 그날이 지나면 화면에서 사라진다 (expires_at = 다음 날 0시).
 */
@Service
public class DeadlineService {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final DashboardService dashboardService;
    private final ApprovalService approvalService;
    private final SiteRepository siteRepository;
    private final SiteMemberRepository siteMemberRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationRecipientRepository recipientRepository;

    public DeadlineService(DashboardService dashboardService, ApprovalService approvalService,
                           SiteRepository siteRepository, SiteMemberRepository siteMemberRepository,
                           NotificationRepository notificationRepository,
                           NotificationRecipientRepository recipientRepository) {
        this.dashboardService = dashboardService;
        this.approvalService = approvalService;
        this.siteRepository = siteRepository;
        this.siteMemberRepository = siteMemberRepository;
        this.notificationRepository = notificationRepository;
        this.recipientRepository = recipientRepository;
    }

    /** 현장 하나의 오늘 마감 확인. @return 새로 보낸 알림 수 */
    @Transactional
    public int checkSite(Long siteId, LocalDateTime now) {
        Site site = siteRepository.findById(siteId).orElse(null);
        if (site == null || !site.isActive()) {
            return 0;
        }
        LocalDate today = now.toLocalDate();
        LocalDateTime endOfDay = today.plusDays(1).atStartOfDay();
        DashboardService.DayFindings f = dashboardService.findings(site, today, today, now).get(today);
        Set<Long> approvers = approvers(siteId);
        int sent = 0;

        List<Entry> tbm = f.entries().getOrDefault(IncompleteType.TBM_MISSING, List.of());
        if (!tbm.isEmpty() && !now.isBefore(today.atTime(site.getTbmDeadline()))) {
            Set<Long> to = new LinkedHashSet<>(approvers);
            Set<Long> missingWorks = tbm.stream().map(Entry::workId).collect(Collectors.toSet());
            f.works().stream().filter(w -> missingWorks.contains(w.getId())).map(Work::getWriterId)
                    .filter(Objects::nonNull).forEach(to::add);
            String msg = "작업 " + tbm.size() + "건의 TBM 이 마감(" + site.getTbmDeadline().format(HM) + ")까지 기록되지 않았습니다.";
            sent += send(siteId, "DEADLINE:TBM:" + siteId + ":" + today, NotificationType.DEADLINE, "TBM 미실시", msg,
                    tbm.size() == 1 ? NotificationTarget.WORK : null, tbm.size() == 1 ? tbm.get(0).workId() : null,
                    to, now, endOfDay);
        }

        if (!f.entries().getOrDefault(IncompleteType.DAILY_LOG_MISSING, List.of()).isEmpty()
                && !now.isBefore(today.atTime(site.getDailyLogDeadline()))) {
            String msg = today + " 안전일지가 마감(" + site.getDailyLogDeadline().format(HM) + ")까지 작성되지 않았습니다.";
            sent += send(siteId, "DEADLINE:DAILY_LOG:" + siteId + ":" + today, NotificationType.DEADLINE, "안전일지 미작성",
                    msg, null, null, approvers, now, endOfDay);
        }

        for (Entry a : dashboardService.correctiveEntries(siteId, today)) {
            if (!a.overdue()) {
                continue;
            }
            Long assignee = dashboardService.assigneeOf(a.targetId());
            Set<Long> to = assignee != null ? Set.of(assignee) : approvers;
            sent += send(siteId, "ACTION_DUE:" + a.targetId() + ":" + today, NotificationType.ACTION_DUE, "개선조치 기한 지남",
                    a.title() + " · " + a.detail(), NotificationTarget.CORRECTIVE_ACTION, a.targetId(), to, now, null);
        }
        return sent;
    }

    /** 결재 마감 지난 결재 재알림 (모든 현장) */
    @Transactional
    public int remindApprovals(LocalDateTime now) {
        return approvalService.remindOverdue(now);
    }

    private Set<Long> approvers(Long siteId) {
        int need = ApprovalService.rankOf(ApproverRole.APPROVER);
        return siteMemberRepository.findBySiteIdAndStatus(siteId, "ACTIVE").stream()
                .filter(m -> ApprovalService.rankOf(m.getRole().name()) >= need)
                .map(SiteMember::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 같은 키 알림이 이미 있거나 받을 사람이 없으면 보내지 않는다. @return 보냈으면 1 */
    private int send(Long siteId, String key, NotificationType type, String title, String message,
                     NotificationTarget target, Long targetId, Set<Long> to, LocalDateTime now, LocalDateTime expiresAt) {
        if (to.isEmpty() || notificationRepository.findByDedupeKey(key).isPresent()) {
            return 0;
        }
        Notification n = Notification.of(siteId, type, NotificationSeverity.WARN, title,
                message.length() <= 500 ? message : message.substring(0, 500), now).dedupeKey(key);
        if (target != null && targetId != null) {
            n.about(target, targetId);
        }
        if (expiresAt != null) {
            n.expiresAt(expiresAt);
        }
        Notification saved = notificationRepository.save(n);
        to.forEach(userId -> recipientRepository.save(new NotificationRecipient(saved, userId)));
        return 1;
    }
}
