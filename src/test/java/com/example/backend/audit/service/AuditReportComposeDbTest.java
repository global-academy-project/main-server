package com.example.backend.audit.service;

import com.example.backend.agent.client.AgentClient;
import com.example.backend.agent.client.AgentReply;
import com.example.backend.audit.dto.AuditReportResponse;
import com.example.backend.audit.dto.CreateAuditReportRequest;
import com.example.backend.audit.entity.AuditReport;
import com.example.backend.audit.entity.AuditReportStatus;
import com.example.backend.audit.repository.AuditReportRepository;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 감사 대응 보고서: AI 서버가 구성기 응답을 줄 때 (11/20). 만들기 → 요약 → 검토 필요 + 항목 요약 + 원본(compose_json, V12) 저장.
 * AI 서버는 Mockito 로 대신한다 (받은 reportId·siteId 를 meta 에 그대로 담는 구성기 응답). 실제 DB, 끝나면 롤백.
 */
@SpringBootTest
@Transactional
@DisplayName("감사 대응 보고서 구성기 응답 연결 (실제 DB)")
class AuditReportComposeDbTest {

    @MockitoBean AgentClient agentClient;
    @Autowired AuditReportService service;
    @Autowired AuditReportRepository reportRepository;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired JsonMapper jsonMapper;
    @Autowired EntityManager em;

    private Long adminId;
    private Long siteId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        adminId = userRepository.save(User.bootstrapAdmin("arc-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("구성기현장-" + tag, SiteMode.VERIFY)).getId();
    }

    private void composeReply(boolean sameReport) {
        when(agentClient.auditReport(any())).thenAnswer(call -> {
            Map<?, ?> body = call.getArgument(0);
            long reportId = ((Number) body.get("reportId")).longValue() + (sameReport ? 0 : 1);
            long site = ((Number) body.get("siteId")).longValue();
            return new AgentReply(jsonMapper.readTree("""
                    {"formType": "AUDIT_REPORT", "schemaVersion": "v1", "status": "INCOMPLETE",
                     "meta": {"reportId": %d, "siteId": %d},
                     "groups": [{"no": "1", "seq": 1, "area": "차단",
                                 "items": [{"queryType": "blocked_records", "sentences": [{"text": "차단 기록 0건임."}]}]}],
                     "overall": {"lines": []}, "verification": []}
                    """.formatted(reportId, site)));
        });
    }

    private AuditReportResponse createAndSummarize() {
        LocalDate today = LocalDate.now();
        AuditReportResponse created = service.create(adminId, siteId,
                new CreateAuditReportRequest(today.minusDays(7), today, List.of("blocked_records", "risk_assessment_rate")));
        service.runSummary(created.reportId());   // 커밋 뒤 리스너가 하는 일 (테스트는 롤백이라 직접)
        em.flush();
        em.clear();
        return service.get(adminId, siteId, created.reportId());
    }

    @Test
    @DisplayName("구성기 응답: 검토 필요, 항목 요약에 문장, 검증 없음, 원본을 compose_json 에 저장")
    void savesCompose() {
        composeReply(true);
        AuditReportResponse r = createAndSummarize();

        assertThat(r.status()).isEqualTo(AuditReportStatus.REVIEW_REQUIRED);
        assertThat(r.statusLabel()).isEqualTo("검토 필요");
        assertThat(r.overallVerification()).isNull();
        assertThat(r.failureReason()).isNull();
        assertThat(r.items().get(1).path("queryType").asString()).isEqualTo("blocked_records");
        assertThat(r.items().get(1).path("summary").asString()).isEqualTo("차단 기록 0건임.");
        assertThat(r.items().get(1).path("verification").isNull()).isTrue();
        AuditReport saved = reportRepository.findById(r.reportId()).orElseThrow();
        assertThat(jsonMapper.readTree(saved.getComposeJson()).path("status").asString()).isEqualTo("INCOMPLETE");
    }

    @Test
    @DisplayName("다른 보고서의 응답이면 요약 실패, 원본 저장 안 함, 다시 요약 가능")
    void mismatchFails() {
        composeReply(false);
        AuditReportResponse r = createAndSummarize();

        assertThat(r.status()).isEqualTo(AuditReportStatus.FAILED);
        assertThat(r.failureReason()).isEqualTo("AI 서버 응답이 요청과 맞지 않습니다.");
        assertThat(r.summaryRetryable()).isTrue();
        assertThat(reportRepository.findById(r.reportId()).orElseThrow().getComposeJson()).isNull();
    }
}
