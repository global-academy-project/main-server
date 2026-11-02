package com.example.backend.notification.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.notification.dto.NotificationItem;
import com.example.backend.notification.dto.NotificationList;
import com.example.backend.notification.dto.NotificationPreferenceItem;
import com.example.backend.notification.entity.Notification;
import com.example.backend.notification.entity.NotificationPreference;
import com.example.backend.notification.entity.NotificationPreferenceId;
import com.example.backend.notification.entity.NotificationRecipient;
import com.example.backend.notification.entity.NotificationType;
import com.example.backend.notification.repository.NotificationPreferenceRepository;
import com.example.backend.notification.repository.NotificationRecipientRepository;
import com.example.backend.notification.repository.NotificationRepository;
import com.example.backend.site.service.SiteAccess;
import com.example.backend.site.service.SiteAccessService;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 내 알림: 목록·안 읽은 수·읽음·모두 읽음·끄기, 종류별 받기 설정.
 * <ul>
 *   <li>보이지 않는 알림: 끈 것, 만료된 것(expires_at 지남), 웹 알림을 끈 종류, 지금 권한이 없는 현장의 알림</li>
 *   <li>읽음·끄기는 내 알림만 (남의 id 면 404)</li>
 *   <li>안전 필수 알림(BLOCKED·NEAR_MISS_WARNING·APPROVAL_REQUEST)은 웹 알림을 끌 수 없다</li>
 * </ul>
 * 실시간 푸시(SSE)는 아직 없다. 화면은 목록·안 읽은 수를 주기적으로 조회한다.
 */
@Service
public class NotificationService {

    private final NotificationRecipientRepository recipientRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;
    private final SiteAccessService siteAccessService;
    private final Clock clock;
    private final int maxRows;

    public NotificationService(NotificationRecipientRepository recipientRepository,
                               NotificationRepository notificationRepository,
                               NotificationPreferenceRepository preferenceRepository, UserRepository userRepository,
                               SiteAccessService siteAccessService, Clock clock,
                               @Value("${app.notification.max-rows}") int maxRows) {
        this.recipientRepository = recipientRepository;
        this.notificationRepository = notificationRepository;
        this.preferenceRepository = preferenceRepository;
        this.userRepository = userRepository;
        this.siteAccessService = siteAccessService;
        this.clock = clock;
        this.maxRows = maxRows;
    }

    /** 목록 (최신순, 최대 yaml app.notification.max-rows). siteId 를 주면 그 현장 알림과 현장 없는 알림만 */
    @Transactional(readOnly = true)
    public NotificationList list(Long userId, Long siteId, boolean unreadOnly) {
        List<View> views = visible(userId);
        List<NotificationItem> items = views.stream()
                .filter(v -> siteId == null || v.n().getSiteId() == null || v.n().getSiteId().equals(siteId))
                .filter(v -> !unreadOnly || v.r().isUnread())
                .map(View::item).toList();
        long unread = views.stream().filter(v -> v.r().isUnread()).count();
        return new NotificationList(items, unread);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return visible(userId).stream().filter(v -> v.r().isUnread()).count();
    }

    @Transactional
    public NotificationItem read(Long userId, Long recipientId) {
        NotificationRecipient r = mine(userId, recipientId);
        r.read(LocalDateTime.now(clock));
        recipientRepository.saveAndFlush(r);
        return new View(r, notification(r)).item();
    }

    @Transactional
    public long readAll(Long userId) {
        recipientRepository.markAllRead(user(userId).getId(), LocalDateTime.now(clock));
        return unreadCount(userId);
    }

    @Transactional
    public void dismiss(Long userId, Long recipientId) {
        NotificationRecipient r = mine(userId, recipientId);
        r.dismiss(LocalDateTime.now(clock));
        recipientRepository.saveAndFlush(r);
    }

    // ------------------------------------------------------------------ 받기 설정

    /** 모든 종류의 설정 (행이 없는 종류는 기본값: 웹 켜짐, 문자 꺼짐) */
    @Transactional(readOnly = true)
    public List<NotificationPreferenceItem> preferences(Long userId) {
        Map<NotificationType, NotificationPreference> saved = preferenceRepository.findByUserId(user(userId).getId()).stream()
                .collect(Collectors.toMap(NotificationPreference::getType, Function.identity()));
        return Arrays.stream(NotificationType.values()).map(t -> {
            NotificationPreference p = saved.get(t);
            return new NotificationPreferenceItem(t, p == null || p.isWebEnabled(), p != null && p.isSmsEnabled(),
                    NotificationPreference.MANDATORY.contains(t));
        }).toList();
    }

    /** 보낸 종류만 바꾼다. 안전 필수 알림의 웹 알림을 끄려 하면 400 */
    @Transactional
    public List<NotificationPreferenceItem> updatePreferences(Long userId, List<NotificationPreferenceItem> changes) {
        Long uid = user(userId).getId();
        for (NotificationPreferenceItem c : changes) {
            try {
                NotificationPreference p = preferenceRepository.findById(new NotificationPreferenceId(uid, c.type()))
                        .orElse(null);
                if (p == null) {
                    preferenceRepository.save(new NotificationPreference(uid, c.type(), c.webEnabled(), c.smsEnabled()));
                } else {
                    p.change(c.webEnabled(), c.smsEnabled());
                }
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, e.getMessage() + " (" + c.type() + ")");
            }
        }
        preferenceRepository.flush();
        return preferences(userId);
    }

    // ------------------------------------------------------------------ 내부

    private record View(NotificationRecipient r, Notification n) {
        NotificationItem item() {
            return new NotificationItem(r.getId(), n.getId(), n.getSiteId(), n.getType(), n.getSeverity(), n.getTitle(),
                    n.getMessage(), n.getTargetType(), n.getTargetId(), n.getCreatedAt(), r.getReadAt(), n.getExpiresAt());
        }
    }

    /** 끄지 않았고, 만료되지 않았고, 웹 알림을 켠 종류이고, 지금 권한이 있는 현장(또는 현장 없음)의 알림 */
    private List<View> visible(Long userId) {
        User user = user(userId);
        LocalDateTime now = LocalDateTime.now(clock);
        Set<Long> sites = siteAccessService.accessibleSites(user).stream().map(SiteAccess::siteId).collect(Collectors.toSet());
        Set<NotificationType> muted = preferenceRepository.findByUserId(user.getId()).stream()
                .filter(p -> !p.isWebEnabled()).map(NotificationPreference::getType).collect(Collectors.toSet());
        List<NotificationRecipient> rows = recipientRepository.findByUserIdAndDismissedAtIsNullOrderByIdDesc(user.getId(),
                PageRequest.of(0, maxRows));
        Map<Long, Notification> notes = notificationRepository.findAllById(
                        rows.stream().map(NotificationRecipient::getNotificationId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Notification::getId, Function.identity()));
        List<View> out = new ArrayList<>();
        for (NotificationRecipient r : rows) {
            Notification n = notes.get(r.getNotificationId());
            if (n == null || n.isExpired(now) || muted.contains(n.getType())
                    || (n.getSiteId() != null && !sites.contains(n.getSiteId()))) {
                continue;
            }
            out.add(new View(r, n));
        }
        return out;
    }

    private NotificationRecipient mine(Long userId, Long recipientId) {
        return recipientRepository.findById(recipientId)
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "알림을 찾을 수 없습니다."));
    }

    private Notification notification(NotificationRecipient r) {
        return notificationRepository.findById(r.getNotificationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "알림을 찾을 수 없습니다."));
    }

    private User user(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }
}
