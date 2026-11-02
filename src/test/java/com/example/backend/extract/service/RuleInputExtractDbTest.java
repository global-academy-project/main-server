package com.example.backend.extract.service;

import com.example.backend.common.exception.ErrorCode;
import com.example.backend.extract.dto.ExtractRequest;
import com.example.backend.extract.dto.ExtractResponse;
import com.example.backend.rule.entity.EvaluationResult;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.rule.repository.RuleEvaluationRepository;
import com.example.backend.rule.service.RuleCheckResult;
import com.example.backend.site.dto.ZoneRequest;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.site.service.ZoneService;
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

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

/** 문장 → 규칙 입력 추출 + 미리 판정 API (실제 DB, 판정 저장 안 함) */
@SpringBootTest
@Transactional
@DisplayName("문장 → 규칙 입력 추출 API (실제 DB)")
class RuleInputExtractDbTest {

    @Autowired RuleInputExtractService service;
    @Autowired ZoneService zoneService;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired RuleEvaluationRepository evaluationRepository;

    private Long adminId;
    private Long siteId;
    private Long zoneId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        adminId = userRepository.save(User.bootstrapAdmin("ex-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("추출현장-" + tag, SiteMode.VERIFY)).getId();
        zoneId = zoneService.create(adminId, siteId, new ZoneRequest("T-101", "저장탱크", null, true, null, null)).zoneId();
    }

    @Test
    @DisplayName("시나리오 B 문장: 화기·밀폐를 찾고 산소 17.5%로 둘 다 미리 판정 BLOCK, 판정은 저장하지 않음")
    void scenarioB() {
        long before = evaluationRepository.count();

        ExtractResponse r = service.extract(adminId, new ExtractRequest(siteId,
                "T-101 탱크 안에서 용접할 건데 산소 농도가 17.5%야", null));

        assertThat(r.extraction().zoneId()).isEqualTo(zoneId);
        assertThat(r.input()).containsEntry("measurement", Map.of("O2", new java.math.BigDecimal("17.5")));
        assertThat(r.previews()).extracting(ExtractResponse.Preview::workType)
                .containsExactlyInAnyOrder(WorkType.HOT, WorkType.CONFINED);
        RuleCheckResult confined = r.previews().stream().filter(p -> p.workType() == WorkType.CONFINED).findFirst()
                .orElseThrow().check();
        assertThat(confined.result()).isEqualTo(EvaluationResult.BLOCK);
        assertThat(confined.evaluationId()).isNull();
        assertThat(confined.violations()).extracting(RuleCheckResult.Violation::ruleCode).contains("CONFINED_O2_RANGE");
        assertThat(r.extractor()).isEqualTo("RULE:v1");
        assertThat(evaluationRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("작업 종류를 정해 보내면 그 종류로만 미리 판정, 현장 권한 없으면 403")
    void fixedType() {
        ExtractResponse r = service.extract(adminId, new ExtractRequest(siteId, "가연성 가스 0%LEL, 화재감시자 김철수", WorkType.HOT));

        assertThat(r.previews()).singleElement().satisfies(p -> assertThat(p.workType()).isEqualTo(WorkType.HOT));
        assertThat(r.input()).containsEntry("form", Map.of("watcher_name", "김철수"));

        User u = new User("ex-" + UUID.randomUUID() + "@example.com", "HASH", "남");
        u.verifyEmail(EmailVerifiedVia.ADMIN, LocalDateTime.now());
        u.approve(UserRole.MEMBER);
        Long outsider = userRepository.save(u).getId();
        expectCode(() -> service.extract(outsider, new ExtractRequest(siteId, "산소 20%", null)), ErrorCode.SITE_NOT_ALLOWED);
    }
}
