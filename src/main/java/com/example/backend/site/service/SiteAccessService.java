package com.example.backend.site.service;

import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMember;
import com.example.backend.site.repository.SiteMemberRepository;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 누가 어느 현장을 볼 수 있는가.
 *  - 회사 관리자(ADMIN): 운영 중인 모든 현장
 *  - 일반 회원(MEMBER): site_members 에서 ACTIVE 인 현장 중 운영 중인 것
 * 다른 기능(작업·허가·기록)에서도 "이 현장에 권한이 있나"는 여기를 쓴다.
 */
@Service
@Transactional(readOnly = true)
public class SiteAccessService {

    private final SiteRepository siteRepository;
    private final SiteMemberRepository siteMemberRepository;

    public SiteAccessService(SiteRepository siteRepository, SiteMemberRepository siteMemberRepository) {
        this.siteRepository = siteRepository;
        this.siteMemberRepository = siteMemberRepository;
    }

    public List<SiteAccess> accessibleSites(User user) {
        if (user.getRole() == UserRole.ADMIN) {
            return siteRepository.findByActiveTrueOrderByNameAsc().stream()
                    .map(s -> new SiteAccess(s.getId(), s.getName(), "ADMIN"))
                    .toList();
        }
        List<SiteMember> members = siteMemberRepository.findByUserIdAndStatus(user.getId(), "ACTIVE");
        Map<Long, Site> sites = siteRepository.findAllById(members.stream().map(SiteMember::getSiteId).toList())
                .stream().filter(Site::isActive).collect(Collectors.toMap(Site::getId, Function.identity()));
        return members.stream()
                .filter(m -> sites.containsKey(m.getSiteId()))
                .map(m -> new SiteAccess(m.getSiteId(), sites.get(m.getSiteId()).getName(), m.getRole().name()))
                .sorted(Comparator.comparing(SiteAccess::name))
                .toList();
    }

    public boolean canAccess(User user, Long siteId) {
        return accessibleSites(user).stream().anyMatch(s -> s.siteId().equals(siteId));
    }
}
