package com.example.backend.site.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 현장별 권한 (대시보드 현장 드롭다운의 원천). 테이블 규칙은 V2 site_members.
 * 권한을 회수할 때는 행을 지우지 않고 active=false(status INACTIVE)로 둔다 (누가 언제 줬는지 남기기 위해).
 */
@Entity
@Table(name = "site_members")
@IdClass(SiteMemberId.class)
public class SiteMember {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "site_id")
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private SiteRole role;

    /** ACTIVE | INACTIVE (DB ck_sm_status) */
    @Column(nullable = false, length = 10)
    private String status = "ACTIVE";

    @Column(name = "granted_by", nullable = false)
    private Long grantedBy;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    protected SiteMember() {
    }

    public SiteMember(Long userId, Long siteId, SiteRole role, Long grantedBy) {
        this.userId = userId;
        this.siteId = siteId;
        this.role = role;
        this.grantedBy = grantedBy;
    }

    /** 권한을 다시 주거나 역할을 바꾼다 */
    public void grant(SiteRole role, Long grantedBy) {
        this.role = role;
        this.status = "ACTIVE";
        this.grantedBy = grantedBy;
    }

    public void revoke() {
        this.status = "INACTIVE";
    }

    public boolean isActive() { return "ACTIVE".equals(status); }
    public Long getUserId() { return userId; }
    public Long getSiteId() { return siteId; }
    public SiteRole getRole() { return role; }
    public Long getGrantedBy() { return grantedBy; }
}
