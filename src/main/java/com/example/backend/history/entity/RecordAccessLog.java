package com.example.backend.history.entity;

import com.example.backend.common.util.JsonText;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 열람 기록: 개인정보(전화번호)·감사 자료·내보내기를 누가 봤나. 테이블 규칙은 V6 record_access_logs.
 * 추가만 가능하다: 수정·삭제는 V9 trg_ral_* 가 막는다.
 */
@Entity
@Table(name = "record_access_logs")
public class RecordAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "site_id", updatable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "resource_type", nullable = false, length = 20, updatable = false)
    private AccessResource resourceType;

    /** 목록 조회·질의는 null */
    @Column(name = "resource_id", updatable = false)
    private Long resourceId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10, updatable = false)
    private AccessAction action;

    /** 질의 조건, 내보낸 범위 등 (JSON 문자열) */
    @Column(updatable = false, columnDefinition = "json")
    private String detail;

    @Column(length = 45, updatable = false)
    private String ip;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected RecordAccessLog() {
    }

    public RecordAccessLog(Long userId, Long siteId, AccessResource resourceType, Long resourceId,
                           AccessAction action, String detail, String ip) {
        if (userId == null || resourceType == null || action == null) {
            throw new IllegalArgumentException("열람한 사람·대상 종류·행위가 필요합니다.");
        }
        if (ip != null && ip.length() > 45) {
            throw new IllegalArgumentException("IP 주소 형식이 올바르지 않습니다.");
        }
        this.userId = userId;
        this.siteId = siteId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.action = action;
        this.detail = JsonText.objectOrNull(detail, "열람 조건");
        this.ip = ip;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getSiteId() { return siteId; }
    public AccessResource getResourceType() { return resourceType; }
    public Long getResourceId() { return resourceId; }
    public AccessAction getAction() { return action; }
    public String getDetail() { return detail; }
    public String getIp() { return ip; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
