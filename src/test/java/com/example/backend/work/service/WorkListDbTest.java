package com.example.backend.work.service;

import com.example.backend.common.exception.ErrorCode;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.work.dto.CreateWorkRequest;
import com.example.backend.work.dto.WorkListItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

/** 현장 작업 목록: 기간·순서·기간 제한 (실제 DB, 끝나면 롤백) */
@SpringBootTest
@Transactional
@DisplayName("작업 목록 (실제 DB)")
class WorkListDbTest {

    @Autowired WorkService workService;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;

    private Long userId;
    private Long siteId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        userId = userRepository.save(User.bootstrapAdmin("work-list-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("목록현장-" + tag, SiteMode.GUIDE)).getId();
    }

    private Long work(LocalDate date, int hour, String title) {
        return workService.create(userId, new CreateWorkRequest(siteId, date, title, null, WorkType.GENERAL,
                date.atTime(hour, 0), date.atTime(hour + 1, 0), 2)).workId();
    }

    @Test
    @DisplayName("기간 안의 작업만 날짜·시작 시각 순으로, 문서가 없으면 빈 목록·null")
    void range_ordered() {
        LocalDate d1 = LocalDate.of(2026, 11, 2);
        LocalDate d2 = LocalDate.of(2026, 11, 3);
        Long late = work(d1, 15, "오후 작업");
        Long early = work(d1, 9, "오전 작업");
        Long next = work(d2, 8, "다음날 작업");
        work(LocalDate.of(2026, 11, 10), 9, "기간 밖");

        assertThat(workService.list(userId, siteId, d1, d2)).extracting(WorkListItem::workId)
                .containsExactly(early, late, next);
        WorkListItem one = workService.list(userId, siteId, d1, null).get(0);
        assertThat(one.permits()).isEmpty();
        assertThat(one.riskAssessment()).isNull();
        assertThat(one.tbm()).isNull();
        assertThat(workService.list(userId, siteId, null, d2)).extracting(WorkListItem::workId).containsExactly(next);
    }

    @Test
    @DisplayName("to 가 from 보다 앞이거나 yaml 최대 기간(31일)을 넘으면 400")
    void invalidRange() {
        LocalDate d = LocalDate.of(2026, 11, 2);
        expectCode(() -> workService.list(userId, siteId, d, d.minusDays(1)), ErrorCode.INVALID_REQUEST);
        expectCode(() -> workService.list(userId, siteId, d, d.plusDays(31)), ErrorCode.INVALID_REQUEST);
        assertThat(workService.list(userId, siteId, d, d.plusDays(30))).isEmpty();
    }
}
