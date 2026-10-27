package com.example.backend.auth.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 이전 비밀번호 해시. 최근 것과 같은 비밀번호로 바꾸지 못하게 한다. 테이블 규칙은 V2 password_history. */
@Entity
@Table(name = "password_history")
public class PasswordHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected PasswordHistory() {
    }

    public PasswordHistory(Long userId, String passwordHash, LocalDateTime createdAt) {
        this.userId = userId;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getPasswordHash() { return passwordHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
