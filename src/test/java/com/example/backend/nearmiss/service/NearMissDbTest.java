package com.example.backend.nearmiss.service;

import com.example.backend.common.exception.ErrorCode;
import com.example.backend.nearmiss.dto.IncidentSearchRequest;
import com.example.backend.nearmiss.dto.NearMissResponse;
import com.example.backend.nearmiss.dto.ReportNearMissRequest;
import com.example.backend.nearmiss.dto.SaveTagsRequest;
import com.example.backend.nearmiss.dto.SaveTagsRequest.Tag;
import com.example.backend.nearmiss.dto.TagCodeResponse;
import com.example.backend.nearmiss.entity.NearMissStatus;
import com.example.backend.site.dto.ZoneRequest;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMember;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.entity.SiteRole;
import com.example.backend.site.repository.SiteMemberRepository;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.site.service.ZoneService;
import com.example.backend.tag.entity.RecordTag;
import com.example.backend.tag.entity.RecordType;
import com.example.backend.tag.entity.TagAxis;
import com.example.backend.tag.entity.TagSource;
import com.example.backend.tag.repository.RecordTagRepository;
import com.example.backend.user.entity.EmailVerifiedVia;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserRole;
import com.example.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

/** 아차사고 보고·태그 검토·관련 사고 검색 (실제 DB, 끝나면 롤백) */
@SpringBootTest
@Transactional
@DisplayName("아차사고 API (실제 DB)")
class NearMissDbTest {

    @Autowired NearMissService service;
    @Autowired RecordTagRepository tagRepository;
    @Autowired ZoneService zoneService;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired SiteMemberRepository siteMemberRepository;

    private String tag;
    private Long adminId;
    private Long siteId;
    private Long reporter;
    private Long otherWriter;
    private Long approver;
    private Long zoneId;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        adminId = userRepository.save(User.bootstrapAdmin("nm-admin-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("아차현장-" + tag, SiteMode.VERIFY)).getId();
        reporter = member("보고자", SiteRole.WRITER);
        otherWriter = member("다른작성자", SiteRole.WRITER);
        approver = member("승인자", SiteRole.APPROVER);
        zoneId = zoneService.create(adminId, siteId, new ZoneRequest("T-101", "저장탱크", null, true, null, null)).zoneId();
    }

    private Long member(String name, SiteRole role) {
        User u = new User("nm-" + UUID.randomUUID() + "@example.com", "HASH", name);
        u.verifyEmail(EmailVerifiedVia.ADMIN, LocalDateTime.now());
        u.approve(UserRole.MEMBER);
        Long id = userRepository.save(u).getId();
        siteMemberRepository.save(new SiteMember(id, siteId, role, adminId));
        return id;
    }

    private NearMissResponse report(String title, String description, Long zone, LocalDateTime at) {
        return service.report(reporter, new ReportNearMissRequest(siteId, zone, null, title, at, description, null));
    }

    private static Tag user(TagAxis axis, String value) {
        return new Tag(axis, value, TagSource.USER, null);
    }

    @Test
    @DisplayName("코드표: 사용 중인 코드만, 이름 포함")
    void tagCodes() {
        assertThat(service.tagCodes()).contains(new TagCodeResponse(TagAxis.SEVERITY, "NEAR_MISS", "아차사고"),
                new TagCodeResponse(TagAxis.ACCIDENT_TYPE, "TRIP", "넘어짐"));
    }

    @Test
    @DisplayName("보고: REPORTED·빈 태그·보고자 이름. 다른 현장 구역 400, 미래 발생 시각 400")
    void reportNearMiss() {
        NearMissResponse n = report(" 맨홀 주변 미끄러짐 ", "바닥 오염", zoneId, LocalDateTime.now().minusHours(1));

        assertThat(n.status()).isEqualTo(NearMissStatus.REPORTED);
        assertThat(n.title()).isEqualTo("맨홀 주변 미끄러짐");
        assertThat(n.zoneName()).isEqualTo("저장탱크");
        assertThat(n.reporterName()).isEqualTo("보고자");
        assertThat(n.tags()).isEmpty();

        Long otherSite = siteRepository.save(new Site("다른현장-" + tag, SiteMode.VERIFY)).getId();
        Long otherZone = zoneService.create(adminId, otherSite, new ZoneRequest("X-1", "다른 구역", null, false, null, null)).zoneId();
        expectCode(() -> report("x", "y", otherZone, LocalDateTime.now()), ErrorCode.INVALID_REQUEST);
        expectCode(() -> report("x", "y", null, LocalDateTime.now().plusHours(1)), ErrorCode.INVALID_REQUEST);
        assertThat(service.list(otherWriter, siteId)).extracting(NearMissResponse::nearMissId).containsExactly(n.nearMissId());
    }

    @Test
    @DisplayName("태그 저장: 전체 교체·TAGGED·버전 증가, 예전 버전 409, 보고자도 승인자도 아니면 403, 승인자는 가능")
    void saveTags() {
        NearMissResponse n = report("미끄러짐", "바닥 오염", zoneId, LocalDateTime.now().minusHours(1));

        NearMissResponse tagged = service.saveTags(reporter, n.nearMissId(), new SaveTagsRequest(n.lockVersion(), List.of(
                user(TagAxis.SEVERITY, "NEAR_MISS"), user(TagAxis.ACCIDENT_TYPE, "TRIP"), user(TagAxis.SUBSTANCE, "108-88-3"))));

        assertThat(tagged.status()).isEqualTo(NearMissStatus.TAGGED);
        assertThat(tagged.lockVersion()).isEqualTo(n.lockVersion() + 1);
        assertThat(tagged.tags()).extracting(NearMissResponse.Tag::axis, NearMissResponse.Tag::value, NearMissResponse.Tag::label)
                .contains(org.assertj.core.groups.Tuple.tuple(TagAxis.ACCIDENT_TYPE, "TRIP", "넘어짐"),
                        org.assertj.core.groups.Tuple.tuple(TagAxis.SUBSTANCE, "108-88-3", null));
        expectCode(() -> service.saveTags(reporter, n.nearMissId(), new SaveTagsRequest(n.lockVersion(), List.of())),
                ErrorCode.CONFLICT_MODIFIED);
        expectCode(() -> service.saveTags(otherWriter, n.nearMissId(), new SaveTagsRequest(tagged.lockVersion(), List.of())),
                ErrorCode.FORBIDDEN);

        NearMissResponse replaced = service.saveTags(approver, n.nearMissId(), new SaveTagsRequest(tagged.lockVersion(),
                List.of(user(TagAxis.ACCIDENT_TYPE, "TRIP"), user(TagAxis.HAZARD, "ENVIRONMENTAL"))));
        assertThat(replaced.tags()).extracting(NearMissResponse.Tag::value).containsExactlyInAnyOrder("TRIP", "ENVIRONMENTAL");
        NearMissResponse cleared = service.saveTags(approver, n.nearMissId(), new SaveTagsRequest(replaced.lockVersion(), List.of()));
        assertThat(cleared.tags()).isEmpty();
    }

    @Test
    @DisplayName("태그 검사: 중대도 2개·없는 코드·CAS 체크섬·중복·사용자 태그 확신도·새 AI 태그는 400")
    void tagValidation() {
        NearMissResponse n = report("미끄러짐", "바닥 오염", null, LocalDateTime.now().minusHours(1));
        int v = n.lockVersion();
        Long id = n.nearMissId();

        expectCode(() -> service.saveTags(reporter, id, new SaveTagsRequest(v, List.of(
                user(TagAxis.SEVERITY, "NEAR_MISS"), user(TagAxis.SEVERITY, "MINOR")))), ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.saveTags(reporter, id, new SaveTagsRequest(v, List.of(user(TagAxis.HAZARD, "NO_SUCH")))),
                ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.saveTags(reporter, id, new SaveTagsRequest(v, List.of(user(TagAxis.SUBSTANCE, "108-88-4")))),
                ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.saveTags(reporter, id, new SaveTagsRequest(v, List.of(
                user(TagAxis.HAZARD, "HUMAN"), user(TagAxis.HAZARD, "HUMAN")))), ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.saveTags(reporter, id, new SaveTagsRequest(v, List.of(
                new Tag(TagAxis.HAZARD, "HUMAN", TagSource.USER, new BigDecimal("0.9"))))), ErrorCode.INVALID_REQUEST);
        expectCode(() -> service.saveTags(reporter, id, new SaveTagsRequest(v, List.of(
                new Tag(TagAxis.HAZARD, "HUMAN", TagSource.AI, new BigDecimal("0.9"))))), ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("AI 태그: 저장된 그대로 보내면 유지(출처·확신도), 확신도를 바꾸면 400, 사용자가 고치면 USER")
    void aiTags() {
        NearMissResponse n = report("미끄러짐", "바닥 오염", null, LocalDateTime.now().minusHours(1));
        tagRepository.saveAndFlush(RecordTag.ai(RecordType.NEAR_MISS, n.nearMissId(), TagAxis.ACCIDENT_TYPE, "TRIP",
                new BigDecimal("0.870")));
        tagRepository.saveAndFlush(RecordTag.ai(RecordType.NEAR_MISS, n.nearMissId(), TagAxis.HAZARD, "HUMAN",
                new BigDecimal("0.400")));

        expectCode(() -> service.saveTags(reporter, n.nearMissId(), new SaveTagsRequest(n.lockVersion(), List.of(
                new Tag(TagAxis.ACCIDENT_TYPE, "TRIP", TagSource.AI, new BigDecimal("0.99"))))), ErrorCode.INVALID_REQUEST);

        NearMissResponse kept = service.saveTags(reporter, n.nearMissId(), new SaveTagsRequest(n.lockVersion(), List.of(
                new Tag(TagAxis.ACCIDENT_TYPE, "TRIP", TagSource.AI, new BigDecimal("0.87")),
                user(TagAxis.HAZARD, "HUMAN"))));

        assertThat(kept.tags()).filteredOn(t -> t.value().equals("TRIP")).singleElement().satisfies(t -> {
            assertThat(t.source()).isEqualTo(TagSource.AI);
            assertThat(t.confidence()).isEqualByComparingTo("0.87");
        });
        assertThat(kept.tags()).filteredOn(t -> t.value().equals("HUMAN")).singleElement().satisfies(t -> {
            assertThat(t.source()).isEqualTo(TagSource.USER);
            assertThat(t.confidence()).isNull();
        });
    }

    @Test
    @DisplayName("검색: 축 안은 OR·축끼리 AND, 글자 포함, 구역 필터, 최근 순, 개수 제한")
    void search() {
        LocalDateTime now = LocalDateTime.now();
        NearMissResponse a = report("맨홀 미끄러짐", "바닥 기름", zoneId, now.minusDays(3));
        NearMissResponse b = report("배관 누출", "밸브 50% 개방 중 누출", zoneId, now.minusDays(2));
        NearMissResponse c = report("계단 넘어짐", "다른 구역", null, now.minusDays(1));
        service.saveTags(reporter, a.nearMissId(), new SaveTagsRequest(a.lockVersion(), List.of(
                user(TagAxis.ACCIDENT_TYPE, "TRIP"), user(TagAxis.HAZARD, "ENVIRONMENTAL"))));
        service.saveTags(reporter, b.nearMissId(), new SaveTagsRequest(b.lockVersion(), List.of(
                user(TagAxis.ACCIDENT_TYPE, "LEAK"), user(TagAxis.HAZARD, "CHEMICAL"))));
        service.saveTags(reporter, c.nearMissId(), new SaveTagsRequest(c.lockVersion(), List.of(
                user(TagAxis.ACCIDENT_TYPE, "TRIP"), user(TagAxis.HAZARD, "HUMAN"))));

        assertThat(search(null, null, filters(List.of("TRIP", "LEAK"), null), 20))
                .containsExactly(c.nearMissId(), b.nearMissId(), a.nearMissId());
        assertThat(search(null, null, filters(List.of("TRIP"), List.of("ENVIRONMENTAL")), 20))
                .containsExactly(a.nearMissId());
        assertThat(search(zoneId, null, filters(List.of("TRIP"), null), 20)).containsExactly(a.nearMissId());
        assertThat(search(null, "50%", null, 20)).containsExactly(b.nearMissId());
        assertThat(search(null, "넘어짐", null, 20)).containsExactly(c.nearMissId());
        assertThat(search(null, null, null, 2)).containsExactly(c.nearMissId(), b.nearMissId());
    }

    @Test
    @DisplayName("CAS 체크섬")
    void cas() {
        assertThat(NearMissService.isCas("108-88-3")).isTrue();     // 톨루엔
        assertThat(NearMissService.isCas("7732-18-5")).isTrue();    // 물
        assertThat(NearMissService.isCas("108-88-4")).isFalse();
        assertThat(NearMissService.isCas("10888-3")).isFalse();
    }

    private static IncidentSearchRequest.Filters filters(List<String> accident, List<String> hazard) {
        return new IncidentSearchRequest.Filters(null, accident, hazard, null, null);
    }

    private List<Long> search(Long zone, String query, IncidentSearchRequest.Filters f, int limit) {
        return service.search(reporter, new IncidentSearchRequest(siteId, zone, query, f, limit)).stream()
                .map(NearMissResponse::nearMissId).toList();
    }
}
