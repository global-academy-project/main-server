package com.example.backend.site.entity;

import jakarta.persistence.*;

/**
 * 현장. 테이블 규칙은 V2 sites.
 * 여기서는 권한·드롭다운에 필요한 컬럼만 읽는다. 현장 관리(사업장 관리 메뉴)를 만들 때 컬럼을 더 매핑하면 된다.
 */
@Entity
@Table(name = "sites")
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private boolean active;

    protected Site() {
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
}
