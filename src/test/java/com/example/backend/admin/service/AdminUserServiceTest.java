package com.example.backend.admin.service;

import com.example.backend.admin.dto.ApproveRequest;
import com.example.backend.admin.dto.SiteGrant;
import com.example.backend.auth.entity.RevokeReason;
import com.example.backend.auth.service.PasswordService;
import com.example.backend.auth.service.RefreshTokenService;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMember;
import com.example.backend.site.entity.SiteRole;
import com.example.backend.site.repository.SiteMemberRepository;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.site.service.SiteAccessService;
import com.example.backend.user.entity.EmailVerifiedVia;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserRole;
import com.example.backend.user.entity.UserStatus;
import com.example.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AdminUserService: 가입 승인·현장 권한·관리자 조치")
class AdminUserServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final LocalDateTime now = LocalDateTime.now(clock);

    private UserRepository userRepository;
    private SiteRepository siteRepository;
    private SiteMemberRepository siteMemberRepository;
    private PasswordService passwordService;
    private RefreshTokenService refreshTokenService;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        siteRepository = mock(SiteRepository.class);
        siteMemberRepository = mock(SiteMemberRepository.class);
        passwordService = mock(PasswordService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        SiteAccessService siteAccessService = new SiteAccessService(siteRepository, siteMemberRepository);
        service = new AdminUserService(userRepository, siteRepository, siteMemberRepository, siteAccessService,
                passwordService, refreshTokenService, clock);
        when(siteRepository.findAllById(anyIterable())).thenReturn(List.of(site(1L, "울산"), site(2L, "여수")));
        when(siteMemberRepository.findByUserId(any())).thenReturn(new ArrayList<>());
    }

    private static Site site(Long id, String name) {
        Site s = org.springframework.beans.BeanUtils.instantiateClass(Site.class);
        ReflectionTestUtils.setField(s, "id", id);
        ReflectionTestUtils.setField(s, "name", name);
        ReflectionTestUtils.setField(s, "active", true);
        return s;
    }

    private User user(Long id, UserStatus status, boolean verified, UserRole role) {
        User u = new User("u" + id + "@example.com", "HASH", "회원" + id);
        ReflectionTestUtils.setField(u, "id", id);
        if (verified) u.verifyEmail(EmailVerifiedVia.LINK, now);
        ReflectionTestUtils.setField(u, "status", status);
        ReflectionTestUtils.setField(u, "role", role);
        when(userRepository.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    @DisplayName("승인: 인증된 PENDING 회원을 ACTIVE 로, 현장 권한 생성(부여자 = 관리자)")
    void approve_ok() {
        User u = user(7L, UserStatus.PENDING, true, UserRole.MEMBER);

        service.approve(1L, 7L, new ApproveRequest(null, List.of(new SiteGrant(1L, SiteRole.WRITER), new SiteGrant(2L, SiteRole.APPROVER))));

        assertThat(u.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(u.getRole()).isEqualTo(UserRole.MEMBER);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SiteMember>> saved = ArgumentCaptor.forClass(List.class);
        verify(siteMemberRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(SiteMember::getSiteId, SiteMember::getRole, SiteMember::getGrantedBy)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, SiteRole.WRITER, 1L),
                        org.assertj.core.groups.Tuple.tuple(2L, SiteRole.APPROVER, 1L));
    }

    @Test
    @DisplayName("승인 거부: 이메일 미인증·이미 ACTIVE → INVALID_STATE, 일반 회원인데 현장 없음 → INVALID_REQUEST, 없는 현장 → SITE_NOT_FOUND")
    void approve_rejects() {
        user(7L, UserStatus.PENDING, false, UserRole.MEMBER);
        expectCode(() -> service.approve(1L, 7L, new ApproveRequest(null, List.of(new SiteGrant(1L, SiteRole.WRITER)))),
                ErrorCode.INVALID_STATE);

        user(8L, UserStatus.ACTIVE, true, UserRole.MEMBER);
        expectCode(() -> service.approve(1L, 8L, new ApproveRequest(null, List.of(new SiteGrant(1L, SiteRole.WRITER)))),
                ErrorCode.INVALID_STATE);

        user(9L, UserStatus.PENDING, true, UserRole.MEMBER);
        expectCode(() -> service.approve(1L, 9L, new ApproveRequest(UserRole.MEMBER, List.of())), ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.approve(1L, 9L, new ApproveRequest(null, List.of(new SiteGrant(99L, SiteRole.WRITER)))),
                ErrorCode.SITE_NOT_FOUND);
    }

    @Test
    @DisplayName("관리자로 승인할 때는 현장 없이도 된다 (모든 현장을 본다)")
    void approve_adminWithoutSites() {
        User u = user(7L, UserStatus.PENDING, true, UserRole.MEMBER);
        service.approve(1L, 7L, new ApproveRequest(UserRole.ADMIN, List.of()));
        assertThat(u.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(u.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("현장 권한 교체: 목록에 있으면 부여·변경, 빠진 현장은 INACTIVE 로 회수")
    void replaceSites() {
        user(7L, UserStatus.ACTIVE, true, UserRole.MEMBER);
        SiteMember ulsan = new SiteMember(7L, 1L, SiteRole.WRITER, 1L);
        SiteMember yeosu = new SiteMember(7L, 2L, SiteRole.WRITER, 1L);
        when(siteMemberRepository.findByUserId(7L)).thenReturn(new ArrayList<>(List.of(ulsan, yeosu)));

        service.replaceSites(5L, 7L, List.of(new SiteGrant(1L, SiteRole.APPROVER)));

        assertThat(ulsan.getRole()).isEqualTo(SiteRole.APPROVER);
        assertThat(ulsan.getGrantedBy()).isEqualTo(5L);
        assertThat(ulsan.isActive()).isTrue();
        assertThat(yeosu.isActive()).isFalse();
    }

    @Test
    @DisplayName("보호: 자기 자신 사용 중지·강등 불가, 마지막 관리자 강등 불가")
    void guards() {
        user(1L, UserStatus.ACTIVE, true, UserRole.ADMIN);
        expectCode(() -> service.deactivate(1L, 1L), ErrorCode.INVALID_STATE);
        expectCode(() -> service.changeRole(1L, 1L, UserRole.MEMBER), ErrorCode.INVALID_STATE);

        user(2L, UserStatus.ACTIVE, true, UserRole.ADMIN);
        when(userRepository.countByRoleAndStatus(UserRole.ADMIN, UserStatus.ACTIVE)).thenReturn(1L);
        expectCode(() -> service.changeRole(1L, 2L, UserRole.MEMBER), ErrorCode.INVALID_STATE);

        when(userRepository.countByRoleAndStatus(UserRole.ADMIN, UserStatus.ACTIVE)).thenReturn(2L);
        service.changeRole(1L, 2L, UserRole.MEMBER);
    }

    @Test
    @DisplayName("사용 중지: INACTIVE + 세션 모두 끊기(ADMIN), 다시 사용: ACTIVE")
    void deactivate_reactivate() {
        User u = user(7L, UserStatus.ACTIVE, true, UserRole.MEMBER);

        service.deactivate(1L, 7L);
        assertThat(u.getStatus()).isEqualTo(UserStatus.INACTIVE);
        verify(refreshTokenService).revokeAll(7L, RevokeReason.ADMIN);

        service.reactivate(7L);
        assertThat(u.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("잠금 해제: ACTIVE + 실패 횟수 0, 대면 확인: 이메일 인증(ADMIN)")
    void unlock_verify() {
        User locked = user(7L, UserStatus.LOCKED, true, UserRole.MEMBER);
        ReflectionTestUtils.setField(locked, "failedLoginCount", 10);
        service.unlock(7L);
        assertThat(locked.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(locked.getFailedLoginCount()).isZero();

        User pending = user(8L, UserStatus.PENDING, false, UserRole.MEMBER);
        service.verifyEmailInPerson(8L);
        assertThat(pending.getEmailVerifiedVia()).isEqualTo(EmailVerifiedVia.ADMIN);
    }

    @Test
    @DisplayName("임시 비밀번호: 공통 규칙으로 지정하고 원문은 응답으로 한 번만")
    void temporaryPassword() {
        User u = user(7L, UserStatus.ACTIVE, true, UserRole.MEMBER);
        var res = service.issueTemporaryPassword(7L);
        verify(passwordService).assignTemporary(eq(u), eq(res.temporaryPassword()));
    }

    @RepeatedTest(20)
    @DisplayName("임시 비밀번호 형식: 12자, 대·소문자·숫자·특수문자 포함 (가입 규칙 통과)")
    void temporaryPassword_format() {
        String p = AdminUserService.newTemporaryPassword();
        assertThat(p).hasSize(12)
                .matches(".*[A-Z].*").matches(".*[a-z].*").matches(".*\\d.*").matches(".*[^A-Za-z\\d].*");
    }

    @Test
    @DisplayName("없는 회원 → USER_NOT_FOUND")
    void notFound() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());
        expectCode(() -> service.unlock(404L), ErrorCode.USER_NOT_FOUND);
        verify(refreshTokenService, never()).revokeAll(any(), any());
    }
}
