package com.example.backend.dashboard.service;

import com.example.backend.site.entity.Site;
import com.example.backend.site.repository.SiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/** 마감 알림 주기 실행 (yaml app.dashboard.deadline-check-interval-ms). 현장 하나가 실패해도 다른 현장은 계속 */
@Component
public class DeadlineScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeadlineScheduler.class);

    private final DeadlineService deadlineService;
    private final SiteRepository siteRepository;
    private final Clock clock;

    public DeadlineScheduler(DeadlineService deadlineService, SiteRepository siteRepository, Clock clock) {
        this.deadlineService = deadlineService;
        this.siteRepository = siteRepository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.dashboard.deadline-check-interval-ms}",
            initialDelayString = "${app.dashboard.deadline-check-interval-ms}")
    public void run() {
        LocalDateTime now = LocalDateTime.now(clock);
        int sent = 0;
        for (Site site : siteRepository.findByActiveTrueOrderByNameAsc()) {
            try {
                sent += deadlineService.checkSite(site.getId(), now);
            } catch (RuntimeException e) {
                log.warn("마감 알림 확인 실패 siteId={}: {}", site.getId(), e.toString());
            }
        }
        try {
            sent += deadlineService.remindApprovals(now);
        } catch (RuntimeException e) {
            log.warn("승인 지연 재알림 실패: {}", e.toString());
        }
        if (sent > 0) {
            log.info("마감 알림 {}건을 보냈습니다.", sent);
        }
    }
}
