package com.example.backend.site.entity;

import java.io.Serializable;
import java.util.Objects;

/** site_members 복합 키 (user_id, site_id) */
public class SiteMemberId implements Serializable {

    private Long userId;
    private Long siteId;

    protected SiteMemberId() {
    }

    public SiteMemberId(Long userId, Long siteId) {
        this.userId = userId;
        this.siteId = siteId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SiteMemberId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(siteId, that.siteId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, siteId);
    }
}
