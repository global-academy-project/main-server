package com.example.backend.user.service;

import com.example.backend.auth.entity.TokenPurpose;
import com.example.backend.auth.mail.AuthMailSender;
import com.example.backend.auth.mail.WebLinks;
import com.example.backend.auth.service.AccountTokenService;
import com.example.backend.auth.service.PasswordService;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.site.service.SiteAccess;
import com.example.backend.site.service.SiteAccessService;
import com.example.backend.user.dto.EmailChangeRequest;
import com.example.backend.user.dto.MeResponse;
import com.example.backend.user.dto.UpdateMeRequest;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("UserService: 내 정보")
class UserServiceTest {

    private UserRepository userRepository;
    private SiteAccessService siteAccessService;
    private PasswordService passwordService;
    private AccountTokenService tokenService;
    private AuthMailSender mailSender;
    private UserService service;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        siteAccessService = mock(SiteAccessService.class);
        passwordService = mock(PasswordService.class);
        tokenService = mock(AccountTokenService.class);
        mailSender = mock(AuthMailSender.class);
        service = new UserService(userRepository, siteAccessService, passwordService, tokenService, mailSender,
                new WebLinks("http://web.test"));

        user = new User("user@example.com", "HASH", "홍길동");
        ReflectionTestUtils.setField(user, "id", 7L);
        ReflectionTestUtils.setField(user, "lockVersion", 3);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(siteAccessService.accessibleSites(user)).thenReturn(List.of(
                new SiteAccess(1L, "울산", "WRITER"), new SiteAccess(2L, "여수", "APPROVER")));
    }

    @Test
    @DisplayName("조회: 현장 목록과 기본 현장 (마지막 선택이 없으면 첫 현장, 있으면 그 현장)")
    void getMe_currentSite() {
        MeResponse me = service.getMe(7L);
        assertThat(me.sites()).hasSize(2);
        assertThat(me.currentSiteId()).isEqualTo(1L);
        assertThat(me.lockVersion()).isEqualTo(3);

        user.selectSite(2L);
        assertThat(service.getMe(7L).currentSiteId()).isEqualTo(2L);

        user.selectSite(99L);   // 권한이 사라진 현장
        assertThat(service.getMe(7L).currentSiteId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("수정: lockVersion 이 같으면 반영, 다르면 CONFLICT_MODIFIED")
    void updateMe() {
        service.updateMe(7L, new UpdateMeRequest(" 김철수 ", "01099998888", "생산팀", "", 3));
        assertThat(user.getName()).isEqualTo("김철수");
        assertThat(user.getPhone()).isEqualTo("01099998888");
        assertThat(user.getJobTitle()).isNull();

        expectCode(() -> service.updateMe(7L, new UpdateMeRequest("x", null, null, null, 2)), ErrorCode.CONFLICT_MODIFIED);
    }

    @Test
    @DisplayName("현장 선택: 권한 있는 현장만, 아니면 SITE_NOT_ALLOWED")
    void selectSite() {
        when(siteAccessService.canAccess(user, 2L)).thenReturn(true);
        service.selectSite(7L, 2L);
        assertThat(user.getLastSiteId()).isEqualTo(2L);

        expectCode(() -> service.selectSite(7L, 99L), ErrorCode.SITE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("이메일 변경 요청: 비밀번호 확인 후 새 주소로 확인 메일 (지금 이메일은 그대로)")
    void emailChange() {
        when(tokenService.issue(7L, TokenPurpose.EMAIL_CHANGE, "new@example.com", "ip"))
                .thenReturn(Optional.of(new AccountTokenService.Issued("CHG", 10)));

        service.requestEmailChange(7L, new EmailChangeRequest(" New@Example.com ", "Passw0rd!"), "ip");

        verify(passwordService).checkCurrent(user, "Passw0rd!");
        verify(mailSender).sendEmailChange("new@example.com", "홍길동", "http://web.test/verify-email-change?token=CHG", 10);
        assertThat(user.getEmail()).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("이메일 변경 거부: 비밀번호 틀림 / 같은 이메일 / 이미 쓰는 이메일")
    void emailChange_rejects() {
        doThrow(new BusinessException(ErrorCode.PASSWORD_MISMATCH)).when(passwordService).checkCurrent(user, "wrong");
        expectCode(() -> service.requestEmailChange(7L, new EmailChangeRequest("new@example.com", "wrong"), "ip"),
                ErrorCode.PASSWORD_MISMATCH);

        expectCode(() -> service.requestEmailChange(7L, new EmailChangeRequest("user@example.com", "ok"), "ip"),
                ErrorCode.INVALID_REQUEST);

        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);
        expectCode(() -> service.requestEmailChange(7L, new EmailChangeRequest("taken@example.com", "ok"), "ip"),
                ErrorCode.EMAIL_DUPLICATED);

        verify(tokenService, never()).issue(any(), any(), any(), any());
    }
}
