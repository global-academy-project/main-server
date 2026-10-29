package com.example.backend.worker.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 작업자. 테이블 규칙은 V2 workers.
 * 로그인 계정(users)과는 선택 연결 (user_id 없을 수 있음: 계정 없는 일용·협력업체 작업자).
 * 작업 배치(work_workers)·TBM 참석·자격 확인의 기준. 지우지 않고 INACTIVE.
 */
@Entity
@Table(name = "workers")
public class Worker {

    /** 숫자만, 0으로 시작 9~11자리 (DB ck_workers_phone) */
    public static final Pattern PHONE = Pattern.compile("^0[0-9]{8,10}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "team_id")
    private Long teamId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 20)
    private String phone;

    /** 예: 용접, 배관, 전기 */
    @Column(name = "job_type", length = 50)
    private String jobType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "employment_type", nullable = false, length = 20)
    private EmploymentType employmentType = EmploymentType.DIRECT;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private WorkerStatus status = WorkerStatus.ACTIVE;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected Worker() {
    }

    public Worker(Long teamId, String name, String phone, String jobType, EmploymentType employmentType) {
        this.teamId = teamId;
        update(name, phone, jobType, employmentType);
    }

    /** phone 은 하이픈을 빼고 저장한다 */
    public void update(String name, String phone, String jobType, EmploymentType employmentType) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("작업자 이름이 비어 있습니다.");
        }
        String p = phone == null || phone.isBlank() ? null : phone.replace("-", "").trim();
        if (p != null && !PHONE.matcher(p).matches()) {
            throw new IllegalArgumentException("전화번호 형식이 아닙니다.");
        }
        this.name = name.trim();
        this.phone = p;
        this.jobType = jobType;
        this.employmentType = employmentType == null ? EmploymentType.DIRECT : employmentType;
    }

    public void changeTeam(Long teamId) { this.teamId = teamId; }

    /** 로그인 계정 연결 (한 계정은 한 작업자에만, DB uk_workers_user) */
    public void linkUser(Long userId) { this.userId = userId; }

    public void unlinkUser() { this.userId = null; }

    public void deactivate() { this.status = WorkerStatus.INACTIVE; }

    public void activate() { this.status = WorkerStatus.ACTIVE; }

    public boolean isActive() { return status == WorkerStatus.ACTIVE; }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getTeamId() { return teamId; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getJobType() { return jobType; }
    public EmploymentType getEmploymentType() { return employmentType; }
    public WorkerStatus getStatus() { return status; }
    public int getLockVersion() { return lockVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
