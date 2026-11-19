package com.example.backend.permit.service;

import com.example.backend.notification.entity.Notification;
import com.example.backend.notification.entity.NotificationRecipient;
import com.example.backend.notification.entity.NotificationSeverity;
import com.example.backend.notification.entity.NotificationTarget;
import com.example.backend.notification.entity.NotificationType;
import com.example.backend.notification.repository.NotificationRecipientRepository;
import com.example.backend.notification.repository.NotificationRepository;
import com.example.backend.permit.entity.Permit;
import com.example.backend.rule.entity.EvaluationResult;
import com.example.backend.rule.entity.RuleSeverity;
import com.example.backend.rule.service.RuleCheckResult;
import com.example.backend.work.entity.Work;
import com.example.backend.work.repository.WorkRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 작업허가 규칙 판정이 차단(BLOCK)이면 차단 알림(BLOCKED, 안전 필수라 웹 알림은 끌 수 없음).
 *  - 받는 사람: 그 작업의 작성자, 판정한 사람 (같으면 한 번)
 *  - 같은 허가서는 하루 한 번만 (다시 판정해 또 차단돼도 알림이 쌓이지 않게. 키 BLOCKED:PERMIT:{id}:{날짜})
 *  - 누르면 그 허가서로 간다 (대상 PERMIT)
 * 판정과 같은 트랜잭션에서 만든다 (판정이 저장되지 않으면 알림도 없음).
 */
@Component
public class PermitBlockedNotifier {

    private final NotificationRepository notificationRepository;
    private final NotificationRecipientRepository recipientRepository;
    private final WorkRepository workRepository;

    public PermitBlockedNotifier(NotificationRepository notificationRepository,
                                 NotificationRecipientRepository recipientRepository, WorkRepository workRepository) {
        this.notificationRepository = notificationRepository;
        this.recipientRepository = recipientRepository;
        this.workRepository = workRepository;
    }

    /** 차단 알림을 만들었으면 true (차단이 아니거나 오늘 이미 보냈으면 false) */
    public boolean notifyIfBlocked(Permit permit, RuleCheckResult result, Long checkerId, LocalDateTime now) {
        if (result.result() != EvaluationResult.BLOCK) {
            return false;
        }
        String key = "BLOCKED:PERMIT:" + permit.getId() + ":" + now.toLocalDate();
        if (notificationRepository.findByDedupeKey(key).isPresent()) {
            return false;
        }
        Work work = workRepository.findById(permit.getWorkId()).orElse(null);
        long blocking = result.violations().stream().filter(v -> v.severity() == RuleSeverity.BLOCK).count();
        String name = work == null ? (permit.getPermitNo() == null ? "작업허가" : permit.getPermitNo()) : work.getTitle();
        String title = "작업허가 판정 차단 · " + name;
        String message = (permit.getPermitNo() == null ? "허가서" : permit.getPermitNo()) + " 규칙 판정이 차단되었습니다"
                + (blocking > 0 ? " (막힌 항목 " + blocking + "개)" : "") + ". 막힌 항목을 고친 뒤 다시 판정해야 승인 요청할 수 있습니다.";
        Notification saved = notificationRepository.save(Notification.of(permit.getSiteId(), NotificationType.BLOCKED,
                        NotificationSeverity.CRITICAL, title.length() <= 100 ? title : title.substring(0, 100), message, now)
                .about(NotificationTarget.PERMIT, permit.getId()).dedupeKey(key));
        Set<Long> recipients = new LinkedHashSet<>();
        if (work != null && work.getWriterId() != null) {
            recipients.add(work.getWriterId());
        }
        if (checkerId != null) {
            recipients.add(checkerId);
        }
        recipients.forEach(id -> recipientRepository.save(new NotificationRecipient(saved, id)));
        return true;
    }
}
