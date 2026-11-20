package com.example.backend.agent.service;

import com.example.backend.agent.dto.AgentMessageItem;
import com.example.backend.agent.dto.AgentRunDetail;
import com.example.backend.agent.dto.AgentRunStarted;
import com.example.backend.agent.dto.StartWorkRegisterRequest;
import com.example.backend.agent.entity.AgentRun;
import com.example.backend.agent.entity.AgentRunStatus;
import com.example.backend.agent.entity.MessageRole;
import com.example.backend.agent.repository.AgentRunRepository;
import com.example.backend.audit.entity.AuditReport;
import com.example.backend.audit.entity.AuditReportStatus;
import com.example.backend.audit.repository.AuditReportRepository;
import com.example.backend.audit.service.AuditReportService;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMember;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.entity.SiteRole;
import com.example.backend.site.repository.SiteMemberRepository;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.user.entity.EmailVerifiedVia;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserRole;
import com.example.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 작업 등록 대화의 감사 분기 (AI 서버 route.target=AUDIT_REPORT): 감사 대응 보고서 만들기·같은 보고서 연결·거절 (실제 DB, 끝나면 롤백).
 * 테스트는 커밋되지 않으므로 AI 서버 응답을 writer 에 직접 넣는다 (AgentRunFlowDbTest 와 같은 방식).
 */
@SpringBootTest
@Transactional
@DisplayName("작업 등록 대화의 감사 분기 (실제 DB)")
class AgentAuditRouteDbTest {

    /** ai-model WorkRegisterResult 감사 분기 모양 (drafts 없음, steps 비어 있음). %s = dateFrom, dateTo */
    private static final String ROUTE = """
            {"runStatus": "DONE", "slots": {}, "classification": {"workTypes": [], "highRisk": null,
             "requiredPermits": [], "canStartBeforeApproval": false},
             "drafts": null, "citations": [], "verification": [], "guidance": [], "llmSources": {},
             "route": {"target": "AUDIT_REPORT", "dateFrom": "%s", "dateTo": "%s",
                       "message": "%s ~ %s 기간의 감사 대응 보고서를 만들게요. 완료되면 감사 대응 화면에서 확인할 수 있어요."},
             "steps": []}
            """;

    /** 기간을 묻는 되묻기 (메인 서버 변경 없이 기존 되묻기로 처리) */
    private static final String ASK = """
            {"runStatus": "WAITING_INPUT", "slots": {"pendingText": "감사 대응 보고서 만들어줘"},
             "ask": {"message": "어느 기간의 감사 대응 보고서를 만들까요? 예: 지난달",
                     "missingFields": [{"key": "audit_period", "label": "보고서 기간", "question": "어느 기간의 감사 대응 보고서를 만들까요?"}]},
             "classification": {"workTypes": [], "highRisk": null, "requiredPermits": [], "canStartBeforeApproval": false},
             "route": null, "steps": []}
            """;

    @Autowired AgentRunService service;
    @Autowired AgentResultWriter writer;
    @Autowired AgentRunRepository runRepository;
    @Autowired AuditReportRepository reportRepository;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired SiteMemberRepository siteMemberRepository;
    @Autowired JsonMapper jsonMapper;
    @Autowired Clock clock;
    @Autowired EntityManager em;

    private Long siteId;
    private Long siteAdmin;
    private Long approver;
    private LocalDate from;
    private LocalDate to;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long companyAdmin = userRepository.save(User.bootstrapAdmin("au-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("감사분기현장-" + tag, SiteMode.VERIFY)).getId();
        siteAdmin = member("au-sa-" + tag, SiteRole.SITE_ADMIN, companyAdmin);
        approver = member("au-ap-" + tag, SiteRole.APPROVER, companyAdmin);
        LocalDate today = LocalDate.now(clock);
        from = today.withDayOfMonth(1).minusMonths(1);
        to = from.withDayOfMonth(from.lengthOfMonth());
    }

    private Long member(String local, SiteRole role, Long by) {
        User u = new User(local + "@example.com", "HASH", local);
        u.verifyEmail(EmailVerifiedVia.ADMIN, LocalDateTime.now());
        u.approve(UserRole.MEMBER);
        Long id = userRepository.save(u).getId();
        siteMemberRepository.save(new SiteMember(id, siteId, role, by));
        return id;
    }

    private AgentRunStarted send(Long userId, Long conversationId, String message) {
        return service.startWorkRegister(userId, new StartWorkRegisterRequest(siteId, conversationId, null, message));
    }

    private void finish(Long runId, String json) {
        writer.saveSuccess(runId, jsonMapper.readTree(json), 500);
        em.flush();
        em.clear();
    }

    private String route(LocalDate f, LocalDate t) {
        return ROUTE.formatted(f, t, f, t);
    }

    private String lastAnswer(Long userId, Long conversationId) {
        List<AgentMessageItem> messages = service.getMessages(userId, conversationId);
        AgentMessageItem last = messages.get(messages.size() - 1);
        assertThat(last.role()).isEqualTo(MessageRole.ASSISTANT);
        return last.content();
    }

    private List<AuditReport> reports() {
        return reportRepository.findBySiteIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(siteId);
    }

    @Test
    @DisplayName("현장 관리자: 기간 되묻기 → 기간 답 → 실행 DONE, 보고서(항목 5개, 요약 중)를 만들고 실행에 연결, 답변은 AI 문구")
    void createsReport() {
        AgentRunStarted first = send(siteAdmin, null, "감사 대응 보고서 만들어줘");
        finish(first.runId(), ASK);
        AgentRunDetail asked = service.getRun(siteAdmin, first.runId());
        assertThat(asked.status()).isEqualTo(AgentRunStatus.WAITING_INPUT);
        assertThat(asked.auditReportId()).isNull();
        assertThat(lastAnswer(siteAdmin, first.conversationId())).startsWith("어느 기간의 감사 대응 보고서");
        assertThat(reports()).isEmpty();

        AgentRunStarted second = send(siteAdmin, first.conversationId(), "지난달");
        finish(second.runId(), route(from, to));

        AgentRunDetail d = service.getRun(siteAdmin, second.runId());
        assertThat(d.status()).isEqualTo(AgentRunStatus.DONE);
        assertThat(d.workId()).isNull();
        assertThat(d.auditReportId()).isNotNull();
        assertThat(d.result().path("route").path("target").asString()).isEqualTo("AUDIT_REPORT");
        AuditReport report = reportRepository.findById(d.auditReportId()).orElseThrow();
        assertThat(report.getSiteId()).isEqualTo(siteId);
        assertThat(report.getCreatedBy()).isEqualTo(siteAdmin);
        assertThat(report.getDateFrom()).isEqualTo(from);
        assertThat(report.getDateTo()).isEqualTo(to);
        assertThat(report.getStatus()).isEqualTo(AuditReportStatus.GENERATING);
        assertThat(report.getQueryTypes()).isEqualTo(String.join(",", AuditReportService.ALL_QUERY_TYPES));
        assertThat(report.getTitle()).isEqualTo("감사 대응 보고서 " + from + " ~ " + to);
        AgentRun run = runRepository.findById(second.runId()).orElseThrow();
        assertThat(run.getTargetType()).isEqualTo("AUDIT_REPORT");
        assertThat(lastAnswer(siteAdmin, first.conversationId())).isEqualTo(from + " ~ " + to
                + " 기간의 감사 대응 보고서를 만들게요. 완료되면 감사 대응 화면에서 확인할 수 있어요.");
    }

    @Test
    @DisplayName("같은 현장·작성자·기간이면 새로 만들지 않고 이미 있는 보고서에 연결 (다른 사람이 만든 보고서는 같은 보고서가 아님)")
    void sameReport() {
        AgentRunStarted first = send(siteAdmin, null, "지난달 감사 대응 보고서");
        finish(first.runId(), route(from, to));
        Long reportId = service.getRun(siteAdmin, first.runId()).auditReportId();

        AgentRunStarted again = send(siteAdmin, first.conversationId(), "지난달 감사 대응 보고서 다시");
        finish(again.runId(), route(from, to));
        AgentRunDetail d = service.getRun(siteAdmin, again.runId());
        assertThat(d.status()).isEqualTo(AgentRunStatus.DONE);
        assertThat(d.auditReportId()).isEqualTo(reportId);
        assertThat(reports()).hasSize(1);
        assertThat(lastAnswer(siteAdmin, first.conversationId())).contains("같은 기간의 감사 대응 보고서가 이미 있어요");

        Long companyAdmin = userRepository.save(User.bootstrapAdmin("au2-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com", "HASH", LocalDateTime.now())).getId();
        AgentRunStarted other = send(companyAdmin, null, "지난달 감사 대응 보고서");
        finish(other.runId(), route(from, to));
        assertThat(service.getRun(companyAdmin, other.runId()).auditReportId()).isNotEqualTo(reportId);
        assertThat(reports()).hasSize(2);
    }

    @Test
    @DisplayName("거절: 현장 관리자 미만·미래 기간·최대 일수 초과 → 실행 DONE, 보고서 없음, 연결 없음, 사유 답변")
    void refusals() {
        AgentRunStarted byApprover = send(approver, null, "지난달 감사 대응 보고서");
        finish(byApprover.runId(), route(from, to));
        AgentRunDetail d1 = service.getRun(approver, byApprover.runId());
        assertThat(d1.status()).isEqualTo(AgentRunStatus.DONE);
        assertThat(d1.auditReportId()).isNull();
        assertThat(runRepository.findById(byApprover.runId()).orElseThrow().getTargetType()).isNull();
        assertThat(lastAnswer(approver, byApprover.conversationId())).isEqualTo("감사 대응 보고서는 현장 관리자 이상만 만들 수 있어요.");

        LocalDate today = LocalDate.now(clock);
        AgentRunStarted future = send(siteAdmin, null, "다음 달 감사 대응 보고서");
        finish(future.runId(), route(today, today.plusDays(1)));
        assertThat(service.getRun(siteAdmin, future.runId()).auditReportId()).isNull();
        assertThat(lastAnswer(siteAdmin, future.conversationId())).startsWith("오늘 이후 날짜는 넣을 수 없어요.");

        AgentRunStarted tooLong = send(siteAdmin, null, "2년치 감사 대응 보고서");
        finish(tooLong.runId(), route(today.minusYears(2), today));
        assertThat(service.getRun(siteAdmin, tooLong.runId()).auditReportId()).isNull();
        assertThat(lastAnswer(siteAdmin, tooLong.conversationId())).startsWith("감사 대응 보고서 기간은 366일까지예요.");

        assertThat(reports()).isEmpty();
    }

    @Test
    @DisplayName("route 기간을 읽을 수 없으면 실행 실패 (보고서 없음)")
    void badPeriod() {
        AgentRunStarted started = send(siteAdmin, null, "감사 대응 보고서");
        finish(started.runId(), ROUTE.formatted("지난달", "", "x", "y"));
        AgentRunDetail d = service.getRun(siteAdmin, started.runId());
        assertThat(d.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(d.errorMessage()).contains("AI_BAD_RESPONSE");
        assertThat(reports()).isEmpty();
    }
}
