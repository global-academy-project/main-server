package com.example.backend.nearmiss;

import com.example.backend.corrective.entity.ActionSource;
import com.example.backend.corrective.entity.ActionStatus;
import com.example.backend.corrective.entity.CorrectiveAction;
import com.example.backend.corrective.repository.CorrectiveActionRepository;
import com.example.backend.nearmiss.entity.NearMiss;
import com.example.backend.nearmiss.entity.NearMissStatus;
import com.example.backend.nearmiss.repository.NearMissRepository;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.entity.WorkZone;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.site.repository.WorkZoneRepository;
import com.example.backend.tag.entity.*;
import com.example.backend.tag.repository.RecordTagRepository;
import com.example.backend.tag.repository.TagCodeRepository;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.work.entity.*;
import com.example.backend.work.repository.WorkRepository;
import com.example.backend.work.repository.WorkWarningRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 아차사고·태그·경고·개선 조치 엔티티를 실제 로컬 DB로 확인 (끝나면 롤백).
 * 시나리오 C 흐름: 아차사고 보고 → 5축 태그 → 같은 구역 작업에 경고 → 확인 → 개선 조치 → 확인.
 */
@SpringBootTest
@Transactional
@DisplayName("아차사고·태그·경고·개선 조치 엔티티 DB 통합")
class NearMissEntitiesDbTest {

    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired WorkZoneRepository zoneRepository;
    @Autowired WorkRepository workRepository;
    @Autowired NearMissRepository nearMissRepository;
    @Autowired TagCodeRepository tagCodeRepository;
    @Autowired RecordTagRepository tagRepository;
    @Autowired WorkWarningRepository warningRepository;
    @Autowired CorrectiveActionRepository actionRepository;
    @Autowired EntityManager em;

    private final LocalDateTime now = LocalDateTime.now().withNano(0);
    private final LocalDate today = now.toLocalDate();
    private Long reporterId;
    private Long managerId;
    private Long siteId;
    private WorkZone rack;
    private Work work;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        reporterId = userRepository.save(User.bootstrapAdmin("nm-r-" + tag + "@example.com", "HASH", now)).getId();
        managerId = userRepository.save(User.bootstrapAdmin("nm-m-" + tag + "@example.com", "HASH", now)).getId();
        siteId = siteRepository.save(new Site("테스트현장-" + tag, SiteMode.VERIFY)).getId();
        rack = zoneRepository.save(new WorkZone(siteId, "P-201", "배관랙", "PIPE_RACK", false, null));
        work = workRepository.save(new Work(siteId, today, "배관랙 보온재 교체", null, WorkType.HEIGHT, null, null, 3, reporterId));
        em.flush();
    }

    private NearMiss report(String title, LocalDateTime occurredAt) {
        return nearMissRepository.saveAndFlush(new NearMiss(siteId, rack.getId(), null, occurredAt, title,
                "작업 발판 끝에서 발이 미끄러졌으나 안전대로 추락하지 않음", "작업 중지 후 발판 정리", reporterId, now));
    }

    @Test
    @DisplayName("보고 → 태그 → 경고·확인 → 개선 조치·확인이 저장되고 다시 읽힌다")
    void scenarioC() {
        // 아차사고 2건 (최근 3일 전, 오래된 60일 전)
        NearMiss recent = report("배관랙 발판 미끄러짐", now.minusDays(3));
        report("배관랙 공구 낙하", now.minusDays(60));

        // 5축 태그 (AI 3개 + 사람 1개)
        tagRepository.save(RecordTag.ai(RecordType.NEAR_MISS, recent.getId(), TagAxis.SEVERITY, "NEAR_MISS", new BigDecimal("0.950")));
        tagRepository.save(RecordTag.ai(RecordType.NEAR_MISS, recent.getId(), TagAxis.ACCIDENT_TYPE, "FALL", new BigDecimal("0.870")));
        tagRepository.save(RecordTag.ai(RecordType.NEAR_MISS, recent.getId(), TagAxis.SUBSTANCE, "108-88-3", null));
        tagRepository.save(RecordTag.user(RecordType.NEAR_MISS, recent.getId(), TagAxis.WORK_TYPE, "HEIGHT"));
        recent.markTagged();
        recent.review();

        // 같은 구역 작업에 경고 2개 (아차사고 WARN, 구역 위험 CRITICAL) → 확인
        WorkWarning nmWarn = warningRepository.save(WorkWarning.fromNearMiss(siteId, work.getId(), recent.getId(),
                WarningSeverity.WARN, "3일 전 같은 구역에서 발판 미끄러짐 아차사고"));
        WorkWarning zoneWarn = warningRepository.save(WorkWarning.of(siteId, work.getId(), WarningSource.ZONE_HAZARD,
                WarningSeverity.CRITICAL, "배관랙 상부 난간 일부 해체 상태"));
        nmWarn.acknowledge(reporterId, now, null);

        // 개선 조치: 하나는 확인까지, 하나는 기한 지남
        CorrectiveAction fixed = actionRepository.save(new CorrectiveAction(siteId, ActionSource.NEAR_MISS, recent.getId(),
                "발판 미끄럼 방지 테이프 부착", null, reporterId, today.plusDays(3), managerId));
        fixed.start();
        fixed.complete(now, "미끄럼 방지 테이프 12m 부착");
        fixed.verify(managerId, now);
        actionRepository.save(new CorrectiveAction(siteId, ActionSource.OTHER, null,
                "배관랙 난간 복구", "해체된 난간 재설치", reporterId, today.minusDays(1), managerId));

        em.flush();
        em.clear();

        // 같은 구역 최근 30일 아차사고 → 1건
        List<NearMiss> warnSources = nearMissRepository.findBySiteIdAndZoneIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                siteId, rack.getId(), now.minusDays(30));
        assertThat(warnSources).extracting(NearMiss::getId).containsExactly(recent.getId());
        assertThat(warnSources.get(0).getStatus()).isEqualTo(NearMissStatus.REVIEWED);

        // 태그: 생성 컬럼 code_axis (물질은 NULL), 확신도, 값으로 찾기
        List<RecordTag> tags = tagRepository.findByRecordTypeAndRecordIdOrderByAxisAscIdAsc(RecordType.NEAR_MISS, recent.getId());
        assertThat(tags).hasSize(4);
        RecordTag substance = tags.stream().filter(t -> t.getAxis() == TagAxis.SUBSTANCE).findFirst().orElseThrow();
        assertThat(substance.getCodeAxis()).isNull();
        RecordTag fall = tags.stream().filter(t -> t.getAxis() == TagAxis.ACCIDENT_TYPE).findFirst().orElseThrow();
        assertThat(fall.getCodeAxis()).isEqualTo("ACCIDENT_TYPE");
        assertThat(fall.getConfidence()).isEqualByComparingTo("0.87");
        assertThat(tagRepository.findByRecordTypeAndAxisAndValue(RecordType.NEAR_MISS, TagAxis.SUBSTANCE, "108-88-3"))
                .extracting(RecordTag::getRecordId).contains(recent.getId());

        // 코드표 (V5 초기 값)
        assertThat(tagCodeRepository.findByAxisAndActiveTrueOrderBySortOrderAsc(TagAxis.ACCIDENT_TYPE).get(0).getCode())
                .isEqualTo("FIRE");
        assertThat(tagCodeRepository.existsByAxisAndCodeAndActiveTrue(TagAxis.SEVERITY, "FATAL")).isTrue();

        // 경고: 확인 안 한 긴급 경고 1건
        assertThat(warningRepository.findByWorkIdOrderByShownAtAscIdAsc(work.getId())).hasSize(2);
        assertThat(warningRepository.findByWorkIdAndAcknowledgedAtIsNullOrderByIdAsc(work.getId()))
                .extracting(WorkWarning::getId).containsExactly(zoneWarn.getId());
        assertThat(warningRepository.findById(nmWarn.getId()).orElseThrow().getShownAt()).isNotNull();
        assertThat(warningRepository.existsByWorkIdAndNearMissId(work.getId(), recent.getId())).isTrue();

        // 개선 조치: 확인 완료 1건, 기한 지남 1건
        assertThat(actionRepository.findBySourceTypeAndSourceIdOrderByIdAsc(ActionSource.NEAR_MISS, recent.getId()))
                .extracting(CorrectiveAction::getStatus).containsExactly(ActionStatus.VERIFIED);
        assertThat(actionRepository.findBySiteIdAndStatusInAndDueDateBeforeOrderByDueDateAsc(
                siteId, List.of(ActionStatus.OPEN, ActionStatus.IN_PROGRESS), today))
                .extracting(CorrectiveAction::getTitle).containsExactly("배관랙 난간 복구");
    }

    // ---- DB 차단 (외래키·유니크). 엔티티 검사를 건너뛴 경우에도 DB가 막는지 ----

    @Test
    @DisplayName("코드표에 없는 코드는 태그로 넣을 수 없다 (fk_tag_code)")
    void unknownCodeRejected() {
        NearMiss nm = report("배관랙 발판 미끄러짐", now.minusDays(1));

        assertThatThrownBy(() -> {
            tagRepository.save(RecordTag.ai(RecordType.NEAR_MISS, nm.getId(), TagAxis.ACCIDENT_TYPE, "SLIP", null));
            em.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("같은 아차사고로 같은 작업에 경고를 두 번 띄울 수 없다 (uk_ww_nm)")
    void duplicateNearMissWarningRejected() {
        NearMiss nm = report("배관랙 발판 미끄러짐", now.minusDays(1));
        warningRepository.saveAndFlush(WorkWarning.fromNearMiss(siteId, work.getId(), nm.getId(), null, "첫 경고"));

        assertThatThrownBy(() -> {
            warningRepository.save(WorkWarning.fromNearMiss(siteId, work.getId(), nm.getId(), null, "중복 경고"));
            em.flush();
        }).isInstanceOf(RuntimeException.class);
    }
}
