package com.example.backend.notification.service;

import com.example.backend.common.exception.ErrorCode;
import com.example.backend.notification.dto.NotificationItem;
import com.example.backend.notification.dto.NotificationList;
import com.example.backend.notification.dto.NotificationPreferenceItem;
import com.example.backend.notification.entity.Notification;
import com.example.backend.notification.entity.NotificationRecipient;
import com.example.backend.notification.entity.NotificationSeverity;
import com.example.backend.notification.entity.NotificationTarget;
import com.example.backend.notification.entity.NotificationType;
import com.example.backend.notification.repository.NotificationRecipientRepository;
import com.example.backend.notification.repository.NotificationRepository;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMember;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.entity.SiteRole;
import com.example.backend.site.repository.SiteMemberRepository;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.user.entity.EmailVerifiedVia;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserRole;
import com.example.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

/** 내 알림: 목록·안 읽은 수·읽음·모두 읽음·끄기·받기 설정 (실제 DB, 끝나면 롤백) */
@SpringBootTest
@Transactional
@DisplayName("알림 조회 API (실제 DB)")
class NotificationDbTest {

    @Autowired NotificationService service;
    @Autowired NotificationRepository notificationRepository;
    @Autowired NotificationRecipientRepository recipientRepository;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired SiteMemberRepository siteMemberRepository;

    private Long adminId;
    private Long siteId;
    private Long me;
    private Long other;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        adminId = userRepository.save(User.bootstrapAdmin("nt-admin-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("알림현장-" + tag, SiteMode.VERIFY)).getId();
        me = member("나");
        other = member("남");
    }

    private Long member(String name) {
        User u = new User("nt-" + UUID.randomUUID() + "@example.com", "HASH", name);
        u.verifyEmail(EmailVerifiedVia.ADMIN, LocalDateTime.now());
        u.approve(UserRole.MEMBER);
        Long id = userRepository.save(u).getId();
        siteMemberRepository.save(new SiteMember(id, siteId, SiteRole.APPROVER, adminId));
        return id;
    }

    /** 알림 하나를 to 에게 (created 시각 지정) */
    private Long notify(Long site, NotificationType type, String title, LocalDateTime created, Long... to) {
        Notification n = notificationRepository.save(Notification.of(site, type, NotificationSeverity.WARN, title, title + " 내용",
                created).about(NotificationTarget.APPROVAL, 1L));
        Long last = null;
        for (Long u : to) {
            last = recipientRepository.save(new NotificationRecipient(n, u)).getId();
        }
        return last;
    }

    @Test
    @DisplayName("목록: 내 알림만 최신순, 안 읽은 수, 현장 필터(현장 없는 알림은 늘 포함)")
    void list() {
        LocalDateTime now = LocalDateTime.now();
        notify(siteId, NotificationType.APPROVAL_REQUEST, "먼저", now.minusMinutes(10), me, other);
        notify(null, NotificationType.SYSTEM, "공지", now.minusMinutes(5), me);
        notify(siteId, NotificationType.DEADLINE, "나중", now.minusMinutes(1), me);
        notify(siteId, NotificationType.DEADLINE, "남의 것", now, other);

        NotificationList all = service.list(me, null, false);
        assertThat(all.items()).extracting(NotificationItem::title).containsExactly("나중", "공지", "먼저");
        assertThat(all.unreadCount()).isEqualTo(3);
        Long otherSite = siteRepository.save(new Site("다른현장-" + UUID.randomUUID(), SiteMode.VERIFY)).getId();
        assertThat(service.list(me, otherSite, false).items()).extracting(NotificationItem::title).containsExactly("공지");
    }

    @Test
    @DisplayName("읽음·모두 읽음·끄기: 남의 알림은 404, 끈 알림은 목록에서 사라짐")
    void readAndDismiss() {
        LocalDateTime now = LocalDateTime.now();
        Long a = notify(siteId, NotificationType.APPROVAL_REQUEST, "가", now.minusMinutes(3), me);
        Long b = notify(siteId, NotificationType.APPROVAL_RESULT, "나", now.minusMinutes(2), me);
        Long othersRow = notify(siteId, NotificationType.APPROVAL_RESULT, "다", now.minusMinutes(1), other);

        NotificationItem read = service.read(me, a);
        assertThat(read.readAt()).isNotNull();
        assertThat(service.unreadCount(me)).isEqualTo(1);
        expectCode(() -> service.read(me, othersRow), ErrorCode.NOT_FOUND);

        assertThat(service.readAll(me)).isZero();
        assertThat(service.unreadCount(other)).isEqualTo(1);   // 남의 알림은 그대로

        service.dismiss(me, b);
        assertThat(service.list(me, null, false).items()).extracting(NotificationItem::id).containsExactly(a);
    }

    @Test
    @DisplayName("보이지 않는 알림: 만료된 것, 지금 권한 없는 현장의 것")
    void hidden() {
        LocalDateTime now = LocalDateTime.now();
        Notification expired = notificationRepository.save(Notification.of(siteId, NotificationType.DEADLINE,
                NotificationSeverity.WARN, "만료", "TBM 미실시", now.minusHours(2)).expiresAt(now.minusHours(1)));
        recipientRepository.save(new NotificationRecipient(expired, me));
        Long noAccess = siteRepository.save(new Site("권한없는현장-" + UUID.randomUUID(), SiteMode.VERIFY)).getId();
        notify(noAccess, NotificationType.APPROVAL_REQUEST, "권한 없음", now.minusMinutes(1), me);
        notify(siteId, NotificationType.APPROVAL_REQUEST, "보임", now, me);

        assertThat(service.list(me, null, false).items()).extracting(NotificationItem::title).containsExactly("보임");
        assertThat(service.unreadCount(me)).isEqualTo(1);
    }

    @Test
    @DisplayName("받기 설정: 웹 알림을 끈 종류는 숨김, 안전 필수 알림은 끌 수 없음(400), 모든 종류를 기본값과 함께 돌려줌")
    void preferences() {
        LocalDateTime now = LocalDateTime.now();
        notify(siteId, NotificationType.APPROVAL_RESULT, "결과", now.minusMinutes(1), me);
        notify(siteId, NotificationType.APPROVAL_REQUEST, "요청", now, me);

        List<NotificationPreferenceItem> prefs = service.updatePreferences(me,
                List.of(new NotificationPreferenceItem(NotificationType.APPROVAL_RESULT, false, false, false)));

        assertThat(prefs).hasSize(NotificationType.values().length);
        assertThat(prefs).filteredOn(p -> p.type() == NotificationType.APPROVAL_RESULT).singleElement()
                .satisfies(p -> assertThat(p.webEnabled()).isFalse());
        assertThat(prefs).filteredOn(p -> p.type() == NotificationType.APPROVAL_REQUEST).singleElement()
                .satisfies(p -> assertThat(p.mandatory()).isTrue());
        assertThat(service.list(me, null, false).items()).extracting(NotificationItem::title).containsExactly("요청");
        expectCode(() -> service.updatePreferences(me,
                List.of(new NotificationPreferenceItem(NotificationType.APPROVAL_REQUEST, false, false, true))),
                ErrorCode.INVALID_REQUEST);
    }
}
