package com.example.backend.site.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 현장. 테이블 규칙은 V2 sites. 테이블 전체를 매핑하고, 기능(사업장 관리 메뉴 등)은 담당자가 메서드를 더해 쓴다.
 * 운영 설정: TBM·안전일지 마감 시각, 승인 재알림 간격, 아차사고 경고 기간 (시나리오 C)
 */
@Entity
@Table(name = "sites")
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private SiteMode mode = SiteMode.VERIFY;

    @Column(length = 50)
    private String industry;

    @Column(name = "employee_count")
    private Integer employeeCount;

    @Column(length = 255)
    private String address;

    /** 이 시각까지 TBM 기록이 없으면 마감 알림 */
    @Column(name = "tbm_deadline", nullable = false, columnDefinition = "time")
    private LocalTime tbmDeadline = LocalTime.of(9, 0);

    /** 일일 안전일지 마감 (TBM 마감보다 뒤여야 함) */
    @Column(name = "daily_log_deadline", nullable = false, columnDefinition = "time")
    private LocalTime dailyLogDeadline = LocalTime.of(18, 0);

    /** 승인 요청 후 이 시간(분)이 지나면 승인자에게 재알림 (5 ~ 1440) */
    @Column(name = "approval_due_minutes", nullable = false)
    private int approvalDueMinutes = 60;

    /** 같은 구역 아차사고를 며칠 전까지 경고에 쓸지 (1 ~ 365) */
    @Column(name = "near_miss_warn_days", nullable = false)
    private int nearMissWarnDays = 30;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected Site() {
    }

    public Site(String name, SiteMode mode) {
        rename(name);
        this.mode = mode == null ? SiteMode.VERIFY : mode;
    }

    public void rename(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("현장 이름이 비어 있습니다.");
        }
        this.name = name.trim();
    }

    public void changeMode(SiteMode mode) {
        this.mode = mode;
    }

    public void updateProfile(String industry, Integer employeeCount, String address) {
        if (employeeCount != null && employeeCount < 0) {
            throw new IllegalArgumentException("근로자 수는 0 이상이어야 합니다.");
        }
        this.industry = industry;
        this.employeeCount = employeeCount;
        this.address = address;
    }

    /** 운영 설정. TBM 마감 < 안전일지 마감, 재알림 5~1440분, 아차사고 경고 1~365일 */
    public void updateSettings(LocalTime tbmDeadline, LocalTime dailyLogDeadline, int approvalDueMinutes, int nearMissWarnDays) {
        if (!tbmDeadline.isBefore(dailyLogDeadline)) {
            throw new IllegalArgumentException("TBM 마감은 안전일지 마감보다 앞이어야 합니다.");
        }
        if (approvalDueMinutes < 5 || approvalDueMinutes > 1440) {
            throw new IllegalArgumentException("승인 재알림 간격은 5~1440분입니다.");
        }
        if (nearMissWarnDays < 1 || nearMissWarnDays > 365) {
            throw new IllegalArgumentException("아차사고 경고 기간은 1~365일입니다.");
        }
        this.tbmDeadline = tbmDeadline;
        this.dailyLogDeadline = dailyLogDeadline;
        this.approvalDueMinutes = approvalDueMinutes;
        this.nearMissWarnDays = nearMissWarnDays;
    }

    public void activate() { this.active = true; }

    public void deactivate() { this.active = false; }

    public Long getId() { return id; }
    public String getName() { return name; }
    public SiteMode getMode() { return mode; }
    public String getIndustry() { return industry; }
    public Integer getEmployeeCount() { return employeeCount; }
    public String getAddress() { return address; }
    public LocalTime getTbmDeadline() { return tbmDeadline; }
    public LocalTime getDailyLogDeadline() { return dailyLogDeadline; }
    public int getApprovalDueMinutes() { return approvalDueMinutes; }
    public int getNearMissWarnDays() { return nearMissWarnDays; }
    public boolean isActive() { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
