package com.example.backend.permit.service;

import com.example.backend.common.exception.ErrorCode;
import com.example.backend.permit.dto.AddMeasurementsRequest;
import com.example.backend.permit.dto.CreatePermitRequest;
import com.example.backend.permit.dto.PermitCheckResponse;
import com.example.backend.permit.dto.PermitResponse;
import com.example.backend.permit.dto.UpdatePermitRequest;
import com.example.backend.permit.entity.MeasurementItem;
import com.example.backend.permit.entity.PermitStatus;
import com.example.backend.permit.entity.PermitType;
import com.example.backend.rule.entity.EvaluationResult;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.work.dto.CreateWorkRequest;
import com.example.backend.work.dto.WorkResponse;
import com.example.backend.work.service.WorkService;
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
import java.util.Map;
import java.util.UUID;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 화기 작업허가 흐름을 실제 로컬 DB로 확인 (끝나면 롤백).
 * 작성 → 측정(LEL 12) → 판정 BLOCK → 승인 요청 거부 → 재측정(LEL 3) → 판정 PASS → 승인 요청 → 이후 수정 불가
 */
@SpringBootTest
@Transactional
@DisplayName("화기 작업허가 흐름 (실제 DB)")
class PermitFlowIntegrationTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 29, 13, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 29, 15, 0);

    @Autowired PermitService permitService;
    @Autowired WorkService workService;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager em;

    private Long adminId;
    private Long workId;

    @BeforeEach
    void setUp() {
        User admin = userRepository.save(User.bootstrapAdmin(
                "permit-test-" + UUID.randomUUID() + "@example.com", "HASH", LocalDateTime.now()));
        adminId = admin.getId();
        em.createNativeQuery("INSERT INTO sites (name) VALUES (?1)").setParameter(1, "테스트현장-" + UUID.randomUUID()).executeUpdate();
        Long siteId = ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
        WorkResponse work = workService.create(adminId, new CreateWorkRequest(siteId, LocalDate.of(2026, 10, 29),
                "R-201 하부 배관 플랜지 볼트 교체", "고착 시 그라인더 절단", WorkType.HOT, FROM, TO, 3));
        workId = work.workId();
    }

    private static Map<String, Object> hotForm() {
        return Map.of("extinguisher_ready", true, "spark_prevention", true, "flammable_vapor_removed", true);
    }

    private PermitResponse createHotPermit() {
        return permitService.create(adminId, new CreatePermitRequest(workId, PermitType.HOT, FROM, TO,
                "김감시", "이반장", hotForm()));
    }

    private PermitResponse measureLel(Long permitId, String value, LocalDateTime at) {
        return permitService.addMeasurements(adminId, permitId, new AddMeasurementsRequest(List.of(
                new AddMeasurementsRequest.Item(MeasurementItem.LEL, new BigDecimal(value), at, "플랜지 주변", "박측정"))));
    }

    @Test
    @DisplayName("판정 규칙이 없는 허가(고소)는 판정이 PASS여도 승인 요청할 수 없다")
    void noRules_cannotSubmit() {   // 고소 규칙을 추가하면 규칙이 없는 다른 종류로 바꾼다
        Long id = permitService.create(adminId, new CreatePermitRequest(workId, PermitType.HEIGHT, FROM, TO,
                null, "이반장", Map.of())).permitId();

        PermitCheckResponse r = permitService.check(adminId, id);

        assertThat(r.evaluation().result()).isEqualTo(EvaluationResult.PASS);
        assertThat(r.evaluation().rulesApplied()).isZero();
        assertThat(r.canSubmit()).isFalse();
        assertThat(permitService.get(adminId, id).canSubmit()).isFalse();
        expectCode(() -> permitService.submit(adminId, id), ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("작성: DRAFT, 허가번호 HOT-20261029-001, 같은 작업에 같은 종류 두 번은 안 됨")
    void create() {
        PermitResponse p = createHotPermit();

        assertThat(p.status()).isEqualTo(PermitStatus.DRAFT);
        assertThat(p.permitNo()).isEqualTo("HOT-20261029-001");
        assertThat(p.form()).containsEntry("extinguisher_ready", true);
        assertThat(p.lastEvaluation()).isNull();
        assertThat(p.canSubmit()).isFalse();
        expectCode(this::createHotPermit, ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("판정 없이 승인 요청하면 INVALID_STATE")
    void submitWithoutCheck_rejected() {
        PermitResponse p = createHotPermit();

        expectCode(() -> permitService.submit(adminId, p.permitId()), ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("전체 흐름: LEL 12 → BLOCK → 요청 거부 → 재측정 LEL 3 → PASS → 승인 요청 → 이후 수정 불가")
    void fullFlow() {
        Long id = createHotPermit().permitId();

        // 1) LEL 12 → BLOCK
        measureLel(id, "12", FROM.minusHours(1));
        PermitCheckResponse blocked = permitService.check(adminId, id);
        assertThat(blocked.evaluation().result()).isEqualTo(EvaluationResult.BLOCK);
        assertThat(blocked.evaluation().evaluationId()).isNotNull();
        assertThat(blocked.evaluation().violations()).extracting(v -> v.ruleCode()).containsExactly("HOT_LEL_MAX");
        assertThat(blocked.status()).isEqualTo(PermitStatus.BLOCKED);
        assertThat(blocked.canSubmit()).isFalse();
        expectCode(() -> permitService.submit(adminId, id), ErrorCode.INVALID_STATE);

        // 2) 재측정 LEL 3 → 판정 무효, 작성 중으로
        PermitResponse remeasured = measureLel(id, "3", FROM.minusMinutes(30));
        assertThat(remeasured.status()).isEqualTo(PermitStatus.DRAFT);
        assertThat(remeasured.lastEvaluation()).isNull();
        assertThat(remeasured.measurements()).extracting(PermitResponse.Measurement::remeasure).containsExactly(false, true);

        // 3) 다시 판정 → 최근 값(3)으로 PASS
        PermitCheckResponse passed = permitService.check(adminId, id);
        assertThat(passed.evaluation().result()).isEqualTo(EvaluationResult.PASS);
        assertThat(passed.canSubmit()).isTrue();
        PermitResponse detail = permitService.get(adminId, id);
        assertThat(detail.lastEvaluation().result()).isEqualTo(EvaluationResult.PASS);
        assertThat(detail.lastEvaluation().evaluationId()).isEqualTo(passed.evaluation().evaluationId());

        // 4) 승인 요청 → PENDING, 이후 측정·수정·판정 불가
        PermitResponse submitted = permitService.submit(adminId, id);
        assertThat(submitted.status()).isEqualTo(PermitStatus.PENDING);
        expectCode(() -> measureLel(id, "1", FROM), ErrorCode.INVALID_STATE);
        expectCode(() -> permitService.check(adminId, id), ErrorCode.INVALID_STATE);
        expectCode(() -> permitService.update(adminId, id, new UpdatePermitRequest(FROM, TO, "김감시", null, hotForm(),
                submitted.lockVersion())), ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("화재감시자·조치 없이 판정하면 BLOCK, 각 위반에 근거가 붙는다")
    void missingMeasures_blocked() {
        Long id = permitService.create(adminId, new CreatePermitRequest(workId, PermitType.HOT, FROM, TO,
                null, null, Map.of())).permitId();
        measureLel(id, "0", FROM.minusHours(1));

        PermitCheckResponse r = permitService.check(adminId, id);

        assertThat(r.evaluation().result()).isEqualTo(EvaluationResult.BLOCK);
        assertThat(r.evaluation().violations()).extracting(v -> v.ruleCode())
                .containsExactly("HOT_EXTINGUISHER", "HOT_FIRE_WATCHER", "HOT_FLAMMABLE_VAPOR_REMOVED", "HOT_SPARK_PREVENTION");
        assertThat(r.evaluation().violations()).allSatisfy(v -> assertThat(v.legalBasis()).isNotBlank());
    }

    @Test
    @DisplayName("내용 수정은 lockVersion 이 맞아야 하고, 수정하면 판정이 무효가 된다")
    void update_invalidatesEvaluation() {
        PermitResponse p = createHotPermit();
        measureLel(p.permitId(), "3", FROM.minusHours(1));
        permitService.check(adminId, p.permitId());
        PermitResponse checked = permitService.get(adminId, p.permitId());

        expectCode(() -> permitService.update(adminId, p.permitId(), new UpdatePermitRequest(FROM, TO, "새감시", null,
                hotForm(), checked.lockVersion() + 1)), ErrorCode.CONFLICT_MODIFIED);
        PermitResponse updated = permitService.update(adminId, p.permitId(), new UpdatePermitRequest(FROM, TO, "새감시",
                null, hotForm(), checked.lockVersion()));

        assertThat(updated.watcherName()).isEqualTo("새감시");
        assertThat(updated.lastEvaluation()).isNull();
        assertThat(updated.canSubmit()).isFalse();
    }

    @Test
    @DisplayName("%·%LEL 항목은 100 초과 불가 (ppm 은 가능)")
    void measurementRange() {
        Long id = createHotPermit().permitId();

        expectCode(() -> measureLel(id, "101", FROM), ErrorCode.INVALID_REQUEST);
        PermitResponse ok = permitService.addMeasurements(adminId, id, new AddMeasurementsRequest(List.of(
                new AddMeasurementsRequest.Item(MeasurementItem.CO, new BigDecimal("150"), FROM, null, null))));
        assertThat(ok.measurements()).singleElement().satisfies(m -> assertThat(m.unit()).isEqualTo("PPM"));
    }
}
