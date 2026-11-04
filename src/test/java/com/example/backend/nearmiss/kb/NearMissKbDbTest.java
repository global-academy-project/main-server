package com.example.backend.nearmiss.kb;

import com.example.backend.nearmiss.dto.NearMissResponse;
import com.example.backend.nearmiss.dto.ReportNearMissRequest;
import com.example.backend.nearmiss.dto.SaveTagsRequest;
import com.example.backend.nearmiss.dto.SaveTagsRequest.Tag;
import com.example.backend.nearmiss.service.NearMissService;
import com.example.backend.site.dto.ZoneRequest;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMember;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.entity.SiteRole;
import com.example.backend.site.repository.SiteMemberRepository;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.site.service.ZoneService;
import com.example.backend.tag.entity.TagAxis;
import com.example.backend.tag.entity.TagSource;
import com.example.backend.user.entity.EmailVerifiedVia;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserRole;
import com.example.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 아차사고 태그 저장 → 적재 이벤트, 청크 만들기(실제 DB), 적재 호출 (실제 DB, 끝나면 롤백) */
@SpringBootTest
@Transactional
@RecordApplicationEvents
@DisplayName("아차사고 RAG 적재 (실제 DB)")
class NearMissKbDbTest {

    @Autowired NearMissService service;
    @Autowired NearMissKbDocuments documents;
    @Autowired ZoneService zoneService;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired SiteMemberRepository siteMemberRepository;
    @Autowired ApplicationEvents events;

    private Long siteId;
    private Long reporter;
    private Long zoneId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long adminId = userRepository.save(User.bootstrapAdmin("kb-admin-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("적재현장-" + tag, SiteMode.VERIFY)).getId();
        User u = new User("kb-" + UUID.randomUUID() + "@example.com", "HASH", "보고자");
        u.verifyEmail(EmailVerifiedVia.ADMIN, LocalDateTime.now());
        u.approve(UserRole.MEMBER);
        reporter = userRepository.save(u).getId();
        siteMemberRepository.save(new SiteMember(reporter, siteId, SiteRole.WRITER, adminId));
        zoneId = zoneService.create(adminId, siteId, new ZoneRequest("R-201", "배관 랙", null, true, null, null)).zoneId();
    }

    @Test
    @DisplayName("보고만 하면 적재하지 않고, 사람이 태그를 저장하면 이벤트 → 현장·태그가 실린 청크")
    void tagsSavedThenDocument() {
        NearMissResponse n = service.report(reporter, new ReportNearMissRequest(siteId, zoneId, null, "발판 미끄러짐",
                LocalDateTime.now().minusHours(1), "점검 중 발판이 미끄러져 넘어질 뻔함", "발판 고정"));
        assertThat(events.stream(NearMissTagsSavedEvent.class)).isEmpty();
        assertThat(documents.document(n.nearMissId())).isEmpty();   // 태그가 없으면 적재할 것이 없음

        service.saveTags(reporter, n.nearMissId(), new SaveTagsRequest(n.lockVersion(), List.of(
                new Tag(TagAxis.SEVERITY, "NEAR_MISS", TagSource.USER, null),
                new Tag(TagAxis.ACCIDENT_TYPE, "FALL", TagSource.USER, null),
                new Tag(TagAxis.WORK_TYPE, "HEIGHT", TagSource.USER, null))));
        assertThat(events.stream(NearMissTagsSavedEvent.class).map(NearMissTagsSavedEvent::nearMissId))
                .containsExactly(n.nearMissId());

        KbIngestClient.Chunk c = documents.document(n.nearMissId()).orElseThrow();
        assertThat(c.pointId()).isEqualTo(NearMissKbDocuments.pointId(n.nearMissId()));
        assertThat(c.payload()).containsEntry("site_id", siteId).containsEntry("kb_source_id", n.nearMissId())
                .containsEntry("source_type", "NEAR_MISS").containsEntry("zone_id", zoneId)
                .containsEntry("severity", List.of("NEAR_MISS")).containsEntry("accident_type", List.of("FALL"))
                .containsEntry("work_type", List.of("HEIGHT"));
        assertThat(c.text()).startsWith("발판 미끄러짐. 점검 중").contains("장소: 배관 랙.", "발생형태: 떨어짐.", "작업: 고소.", "중대도: 아차사고.", "즉시 조치: 발판 고정");

        // 적재 호출: 켜져 있으면 kb_accidents 로 한 건, 꺼져 있으면 부르지 않음, 실패해도 예외를 밖으로 내지 않음
        List<String> calls = new ArrayList<>();
        KbIngestClient fake = new KbIngestClient() {
            public boolean enabled() { return true; }
            public int upsert(String collection, List<Chunk> chunks) {
                calls.add(collection + ":" + chunks.get(0).pointId());
                return 1;
            }
        };
        assertThat(new NearMissKbListener(fake, documents).ingest(n.nearMissId())).isTrue();
        assertThat(calls).containsExactly("kb_accidents:" + c.pointId());
        assertThat(new NearMissKbListener(new NoKbIngestClient(), documents).ingest(n.nearMissId())).isFalse();
        KbIngestClient broken = new KbIngestClient() {
            public boolean enabled() { return true; }
            public int upsert(String collection, List<Chunk> chunks) { throw new IllegalStateException("kb-ingest 응답 502"); }
        };
        assertThat(new NearMissKbListener(broken, documents).ingest(n.nearMissId())).isFalse();

        // 태그를 모두 지우면 이벤트 없음
        NearMissResponse latest = service.get(reporter, n.nearMissId());
        service.saveTags(reporter, n.nearMissId(), new SaveTagsRequest(latest.lockVersion(), List.of()));
        assertThat(events.stream(NearMissTagsSavedEvent.class)).hasSize(1);
    }
}
