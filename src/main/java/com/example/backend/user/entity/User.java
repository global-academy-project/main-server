package com.example.backend.user.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 회원. 테이블 규칙은 V2__sites_users_role.sql.
 * 상태 변경은 setter 대신 아래 메서드로만 한다 (DB CHECK 와 같은 규칙을 앱에서도 지키기 위해).
 * updated_at 은 DB가 ON UPDATE 로 채우므로 매핑하지 않는다.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private UserRole role = UserRole.MEMBER;

    @Column(length = 20)
    private String phone;

    @Column(length = 100)
    private String department;

    @Column(name = "job_title", length = 50)
    private String jobTitle;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.PENDING;

    @Column(name = "last_site_id")
    private Long lastSiteId;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount = 0;

    @Column(name = "last_login_at", columnDefinition = "datetime(6)")
    private LocalDateTime lastLoginAt;

    @Column(name = "password_changed_at", columnDefinition = "datetime(6)")
    private LocalDateTime passwordChangedAt;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    @Column(name = "email_verified_at", columnDefinition = "datetime(6)")
    private LocalDateTime emailVerifiedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "email_verified_via", length = 10)
    private EmailVerifiedVia emailVerifiedVia;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected User() {
    }

    public User(String email, String passwordHash, String name) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
    }

    /** 가입 시 선택 항목. 빈 문자열은 null 로 저장한다 (DB 전화번호 CHECK 가 빈 문자열을 거부). */
    public void updateProfile(String phone, String department, String jobTitle) {
        this.phone = blankToNull(phone);
        this.department = blankToNull(department);
        this.jobTitle = blankToNull(jobTitle);
    }

    /** 첫 관리자 생성용: 메일 없이 관리자 확인으로 인증·활성화하고 첫 로그인 때 비밀번호를 바꾸게 한다. */
    public static User bootstrapAdmin(String email, String passwordHash, LocalDateTime now) {
        User u = new User(email, passwordHash, "관리자");
        u.role = UserRole.ADMIN;
        u.verifyEmail(EmailVerifiedVia.ADMIN, now);
        u.status = UserStatus.ACTIVE;
        u.mustChangePassword = true;
        u.passwordChangedAt = now;
        return u;
    }

    /** 이메일 소유 확인. 이미 확인됐으면 처음 기록을 유지한다. 상태는 바꾸지 않는다 (ACTIVE 는 관리자 승인 때). */
    public void verifyEmail(EmailVerifiedVia via, LocalDateTime now) {
        if (this.emailVerifiedAt == null) {
            this.emailVerifiedAt = now;
            this.emailVerifiedVia = via;
        }
    }

    /**
     * 비밀번호가 틀렸을 때. 사용 중(ACTIVE)인 계정만 maxFailures 에 닿으면 잠근다.
     * 승인 전·중지 계정은 어차피 로그인할 수 없으므로 횟수만 센다.
     * @return 이번 실패로 잠겼으면 true
     */
    public boolean recordLoginFailure(int maxFailures) {
        this.failedLoginCount++;
        if (this.status == UserStatus.ACTIVE && this.failedLoginCount >= maxFailures) {
            this.status = UserStatus.LOCKED;
            return true;
        }
        return false;
    }

    public void recordLoginSuccess(LocalDateTime now) {
        this.failedLoginCount = 0;
        this.lastLoginAt = now;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    private static String blankToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getName() { return name; }
    public UserRole getRole() { return role; }
    public String getPhone() { return phone; }
    public String getDepartment() { return department; }
    public String getJobTitle() { return jobTitle; }
    public UserStatus getStatus() { return status; }
    public Long getLastSiteId() { return lastSiteId; }
    public int getFailedLoginCount() { return failedLoginCount; }
    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public LocalDateTime getPasswordChangedAt() { return passwordChangedAt; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public LocalDateTime getEmailVerifiedAt() { return emailVerifiedAt; }
    public EmailVerifiedVia getEmailVerifiedVia() { return emailVerifiedVia; }
    public int getLockVersion() { return lockVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
