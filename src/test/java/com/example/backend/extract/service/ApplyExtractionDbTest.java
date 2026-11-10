package com.example.backend.extract.service;

import com.example.backend.common.exception.ErrorCode;
import com.example.backend.extract.dto.ApplyExtractionRequest;
import com.example.backend.permit.dto.CreatePermitRequest;
import com.example.backend.permit.dto.PermitCheckResponse;
import com.example.backend.permit.dto.PermitResponse;
import com.example.backend.permit.dto.UpdatePermitRequest;
import com.example.backend.permit.entity.MeasurementItem;
import com.example.backend.permit.entity.PermitType;
import com.example.backend.permit.service.PermitService;
import com.example.backend.rule.entity.EvaluationResult;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.rule.repository.RuleEvaluationRepository;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.work.dto.CreateWorkRequest;
import com.example.backend.work.service.WorkService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

/** 확인한 문장 추출값을 허가서에 넣기: 측정값·양식 반영, 원문·추출값 스냅샷이 판정 입력에 남음 (실제 DB) */
@SpringBootTest
@Transactional
@DisplayName("문장 추출값 허가서 반영·스냅샷 (실제 DB)")
class ApplyExtractionDbTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 11, 3, 13, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 11, 3, 15, 0);
    private static final String TEXT = "R-201 플랜지 용접, 가연성 가스 0%LEL, 화재감시자 김철수";

    @Autowired ExtractApplyService service;
    @Autowired PermitService permitService;
    @Autowired WorkService workService;
    @Autowired RuleEvaluationRepository evaluationRepository;
    @Autowired UserRepository userRepository;
    @Autowired JsonMapper jsonMapper;
    @Autowired EntityManager em;

    private Long adminId;
    private Long permitId;

    @BeforeEach
    void setUp() {
        adminId = userRepository.save(User.bootstrapAdmin("ap-" + UUID.randomUUID() + "@example.com", "HASH",
                LocalDateTime.now())).getId();
        em.createNativeQuery("INSERT INTO sites (name) VALUES (?1)").setParameter(1, "추출반영현장-" + UUID.randomUUID()).executeUpdate();
        Long siteId = ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
        Long workId = workService.create(adminId, new CreateWorkRequest(siteId, LocalDate.of(2026, 11, 3), "플랜지 용접",
                null, WorkType.HOT, FROM, TO, 2)).workId();
        permitId = permitService.create(adminId, new CreatePermitRequest(workId, PermitType.HOT, FROM, TO, null, "이반장",
                Map.of("spark_prevention", true))).permitId();
    }

    /** 컨트롤러와 같은 순서: 추출(트랜잭션 밖) → 반영 */
    private PermitResponse apply(ApplyExtractionRequest request) {
        return service.apply(adminId, permitId, request, service.extractFor(adminId, permitId, request.text()));
    }

    private ApplyExtractionRequest request(int lockVersion, Map<String, Object> form) {
        return new ApplyExtractionRequest(lockVersion, TEXT,
                List.of(new ApplyExtractionRequest.Measurement(MeasurementItem.LEL, BigDecimal.ZERO)), form);
    }

    @Test
    @DisplayName("반영: 측정값 추가·감시자 칸·양식 합침, form._source 에 원문·서버 추출값·확인값. 판정 스냅샷에 source 로 남고 미사용 항목엔 안 나옴")
    void applyAndSnapshot() throws Exception {
        PermitResponse before = permitService.get(adminId, permitId);

        PermitResponse after = apply(request(before.lockVersion(),
                Map.of("watcher_name", "김철수", "extinguisher_ready", true, "flammable_vapor_removed", true)));

        assertThat(after.watcherName()).isEqualTo("김철수");
        assertThat(after.form()).containsEntry("spark_prevention", true).containsEntry("extinguisher_ready", true);
        assertThat(after.measurements()).anySatisfy(m -> assertThat(m.item()).isEqualTo(MeasurementItem.LEL));
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) after.form().get("_source");
        assertThat(source).containsEntry("text", TEXT).containsEntry("extractor", "RULE:v1").containsKey("confirmed");
        assertThat(source.get("extracted").toString()).contains("LEL").contains("김철수");

        PermitCheckResponse check = permitService.check(adminId, permitId);
        assertThat(check.evaluation().result()).isEqualTo(EvaluationResult.PASS);
        assertThat(check.evaluation().unusedFields()).noneMatch(f -> f.startsWith("source."));
        JsonNode snapshot = jsonMapper.readTree(evaluationRepository.findById(check.evaluation().evaluationId())
                .orElseThrow().getInputSnapshot());
        assertThat(snapshot.path("source").path("text").asString()).isEqualTo(TEXT);
        assertThat(snapshot.path("form").has("_source")).isFalse();
    }

    @Test
    @DisplayName("화면이 수정 때 _source 를 빼고 보내도 스냅샷은 남는다")
    void sourceKeptOnUpdate() {
        PermitResponse applied = apply(request(permitService.get(adminId, permitId).lockVersion(),
                Map.of("extinguisher_ready", true)));

        PermitResponse updated = permitService.update(adminId, permitId, new UpdatePermitRequest(FROM, TO, "김감시", "이반장",
                Map.of("extinguisher_ready", true, "spark_prevention", true), applied.lockVersion()));

        assertThat(updated.form()).containsKey("_source");
    }

    @Test
    @DisplayName("이 허가 종류에 없는 양식 항목 400, 예전 lockVersion 409")
    void validation() {
        int v = permitService.get(adminId, permitId).lockVersion();
        expectCode(() -> apply(request(v, Map.of("harness_worn", true))), ErrorCode.INVALID_REQUEST);
        expectCode(() -> apply(request(v + 1, Map.of())), ErrorCode.CONFLICT_MODIFIED);
    }
}
