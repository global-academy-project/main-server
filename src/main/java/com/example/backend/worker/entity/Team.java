package com.example.backend.worker.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 팀·업체. 테이블 규칙은 V2 teams. 사내 팀과 외부 협력업체를 같이 담는다.
 * 사업자등록번호는 외부 업체만, 숫자 10자리.
 */
@Entity
@Table(name = "teams")
public class Team {

    private static final Pattern BUSINESS_NO = Pattern.compile("^[0-9]{10}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "team_type", nullable = false, length = 10)
    private TeamType teamType = TeamType.INTERNAL;

    @Column(name = "business_no", length = 10, columnDefinition = "char(10)")
    private String businessNo;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    protected Team() {
    }

    public Team(String name, TeamType teamType, String businessNo) {
        update(name, teamType, businessNo);
    }

    public static Team internal(String name) {
        return new Team(name, TeamType.INTERNAL, null);
    }

    public static Team external(String name, String businessNo) {
        return new Team(name, TeamType.EXTERNAL, businessNo);
    }

    /** businessNo 는 하이픈을 빼고 숫자 10자리로 저장한다 */
    public void update(String name, TeamType teamType, String businessNo) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("팀 이름이 비어 있습니다.");
        }
        TeamType type = teamType == null ? TeamType.INTERNAL : teamType;
        String no = businessNo == null || businessNo.isBlank() ? null : businessNo.replace("-", "").trim();
        if (no != null) {
            if (type != TeamType.EXTERNAL) {
                throw new IllegalArgumentException("사업자등록번호는 외부 업체만 넣을 수 있습니다.");
            }
            if (!BUSINESS_NO.matcher(no).matches()) {
                throw new IllegalArgumentException("사업자등록번호는 숫자 10자리입니다.");
            }
        }
        this.name = name.trim();
        this.teamType = type;
        this.businessNo = no;
    }

    public boolean isExternal() { return teamType == TeamType.EXTERNAL; }

    public void deactivate() { this.active = false; }

    public void activate() { this.active = true; }

    public Long getId() { return id; }
    public String getName() { return name; }
    public TeamType getTeamType() { return teamType; }
    public String getBusinessNo() { return businessNo; }
    public boolean isActive() { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
