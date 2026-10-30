package com.example.backend.site.repository;

import com.example.backend.site.entity.SiteMember;
import com.example.backend.site.entity.SiteMemberId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SiteMemberRepository extends JpaRepository<SiteMember, SiteMemberId> {

    List<SiteMember> findByUserId(Long userId);

    List<SiteMember> findByUserIdIn(Collection<Long> userIds);

    List<SiteMember> findByUserIdAndStatus(Long userId, String status);

    /** 이 현장의 권한자 (결재 알림 받을 사람 찾기) */
    List<SiteMember> findBySiteIdAndStatus(Long siteId, String status);
}
