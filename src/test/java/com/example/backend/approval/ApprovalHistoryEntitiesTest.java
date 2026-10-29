package com.example.backend.approval;

import com.example.backend.approval.entity.*;
import com.example.backend.dailylog.entity.DailyLogStatus;
import com.example.backend.dailylog.entity.DailySafetyLog;
import com.example.backend.form.entity.*;
import com.example.backend.history.entity.*;
import com.example.backend.job.entity.AsyncJob;
import com.example.backend.job.entity.JobStatus;
import com.example.backend.job.entity.JobTarget;
import com.example.backend.job.entity.JobType;
import com.example.backend.notification.entity.*;
import com.example.backend.signature.entity.Signature;
import com.example.backend.signature.entity.SignaturePurpose;
import com.example.backend.signature.entity.SignatureTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 결재·이력·안전일지·알림·서명·양식·임시저장·비동기 작업 엔티티가 DB 규칙을 저장 전에 먼저 막는지 (DB 없이) */
@DisplayName("결재·이력 엔티티 검증 (V6)")
class ApprovalHistoryEntitiesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 13, 0);
    private static final LocalDate D = NOW.toLocalDate();
    private static final String SHA = "a".repeat(64);

    private static Approval pendingApproval() {
        Approval a = new Approval(1L, ApprovalTarget.PERMIT, 10L, 3L, NOW.plusHours(4), NOW);
        ReflectionTestUtils.setField(a, "id", 100L);
        return a;
    }

    @Test
    @DisplayName("결재: 반려는 사유 필수, 결정은 한 번만, 결정 시각은 요청 이후")
    void approval() {
        assertThatThrownBy(() -> new Approval(1L, ApprovalTarget.PERMIT, 10L, 3L, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);   // 기한이 요청 시각과 같음

        Approval a = pendingApproval();
        assertThatThrownBy(() -> a.reject(4L, " ", NOW.plusMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.approve(4L, null, NOW.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.approve(null, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.moveTo(3)).isInstanceOf(IllegalArgumentException.class);
        assertThat(a.isOverdue(NOW.plusHours(5))).isTrue();

        a.moveTo(2);
        a.reject(4L, " 측정값 재확인 필요 ", NOW.plusMinutes(5));
        assertThat(a.getStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(a.getComment()).isEqualTo("측정값 재확인 필요");
        assertThat(a.getApproverId()).isEqualTo(4L);
        assertThat(a.isOverdue(NOW.plusHours(5))).isFalse();
        assertThatThrownBy(() -> a.cancel(null, NOW.plusMinutes(6))).isInstanceOf(IllegalStateException.class);

        Approval c = pendingApproval();
        c.cancel(" ", NOW.plusMinutes(1));
        assertThat(c.getStatus()).isEqualTo(ApprovalStatus.CANCELED);
        assertThat(c.getApproverId()).isNull();
        assertThat(c.getComment()).isNull();
    }

    @Test
    @DisplayName("결재 단계: 1단계만 PENDING, 지정 결재자만 결정, 결정 후 건너뛰기 불가")
    void approvalStep() {
        Approval a = pendingApproval();
        ApprovalStep s1 = new ApprovalStep(a, 1, "부서장 확인", ApproverRole.APPROVER, 4L);
        ApprovalStep s2 = new ApprovalStep(a, 2, "안전관리자 승인", ApproverRole.SITE_ADMIN, null);
        assertThat(s1.getStatus()).isEqualTo(ApprovalStepStatus.PENDING);
        assertThat(s2.getStatus()).isEqualTo(ApprovalStepStatus.WAITING);
        assertThatThrownBy(() -> new ApprovalStep(a, 11, "x", ApproverRole.ADMIN, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovalStep(a, 3, " ", ApproverRole.ADMIN, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> s1.approve(5L, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s2.approve(5L, null, NOW)).isInstanceOf(IllegalStateException.class);
        s1.approve(4L, null, NOW);
        s2.open();
        s2.reject(5L, "보호구 사진 누락", NOW);
        assertThat(s2.isDecided()).isTrue();
        assertThatThrownBy(s2::skip).isInstanceOf(IllegalStateException.class);

        ApprovalStep s3 = new ApprovalStep(a, 3, "공장장", ApproverRole.ADMIN, null);
        s3.skip();
        assertThat(s3.getStatus()).isEqualTo(ApprovalStepStatus.SKIPPED);
    }

    @Test
    @DisplayName("결재선 정책: 단계 1~10, 이름 50자")
    void approvalPolicy() {
        ApprovalPolicy p = new ApprovalPolicy(1L, ApprovalTarget.PERMIT, 1, " 안전관리자 ", ApproverRole.SITE_ADMIN, 3L);
        assertThat(p.getLabel()).isEqualTo("안전관리자");
        assertThatThrownBy(() -> new ApprovalPolicy(1L, ApprovalTarget.PERMIT, 0, "x", ApproverRole.APPROVER, 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.change("x".repeat(51), ApproverRole.APPROVER, 3L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이력·열람 기록: SYSTEM 외에는 행위자 필수, JSON 은 객체만")
    void history() {
        RecordHistory h = RecordHistory.byAgent(1L, HistoryTarget.PERMIT, 10L, HistoryAction.AI_DRAFT, 77L, null,
                "{\"status\":\"DRAFT\"}");
        assertThat(h.getActorType()).isEqualTo(ActorType.AGENT);
        assertThat(h.getBeforeJson()).isNull();
        assertThat(RecordHistory.bySystem(null, HistoryTarget.SITE, 1L, HistoryAction.CREATE, null, null).getActorId())
                .isNull();
        assertThatThrownBy(() -> RecordHistory.byUser(1L, HistoryTarget.WORK, 1L, HistoryAction.UPDATE, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecordHistory.byUser(1L, HistoryTarget.WORK, 1L, HistoryAction.UPDATE, 3L, "[1]", null))
                .isInstanceOf(IllegalArgumentException.class);

        RecordAccessLog log = new RecordAccessLog(3L, 1L, AccessResource.WORKER_PII, 5L, AccessAction.VIEW, null, "10.0.0.1");
        assertThat(log.getIp()).isEqualTo("10.0.0.1");
        assertThatThrownBy(() -> new RecordAccessLog(3L, 1L, AccessResource.RECORD_QUERY, null, AccessAction.QUERY,
                "select", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("안전일지: 작성 주체 필수, 요약 없이 제출 불가, 승인 후 수정 불가")
    void dailyLog() {
        assertThatThrownBy(() -> DailySafetyLog.byAgent(1L, D, null)).isInstanceOf(IllegalArgumentException.class);
        DailySafetyLog log = DailySafetyLog.byAgent(1L, D, 77L);
        assertThatThrownBy(log::submit).isInstanceOf(IllegalStateException.class);

        log.update("화기 2건, 차단 1건", "{\"works\":2,\"blocked\":1}", 3L);
        assertThat(log.getWriterId()).isEqualTo(3L);
        log.submit();
        assertThatThrownBy(() -> log.update(" ", null, 3L)).isInstanceOf(IllegalArgumentException.class);
        log.reopen();
        log.submit();
        log.approve();
        assertThat(log.getStatus()).isEqualTo(DailyLogStatus.APPROVED);
        assertThatThrownBy(() -> log.update("수정", null, 3L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("알림: 이동 기록은 종류·id 함께, 만료는 알림 이후, 저장 후 변경 불가 / 수신: 끄면 읽음도")
    void notification() {
        Notification n = Notification.of(1L, NotificationType.APPROVAL_RESULT, NotificationSeverity.INFO,
                "작업허가 승인", "R-201 화기작업 허가가 승인되었습니다.", NOW).about(NotificationTarget.PERMIT, 10L)
                .by(ActorType.USER, 4L).dedupeKey("APPROVAL_RESULT:PERMIT:10:1");
        assertThat(n.getTargetId()).isEqualTo(10L);
        assertThatThrownBy(() -> n.about(NotificationTarget.PERMIT, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> n.expiresAt(NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> n.by(ActorType.AGENT, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Notification.of(1L, NotificationType.SYSTEM, NotificationSeverity.INFO, "x".repeat(101),
                "m", NOW)).isInstanceOf(IllegalArgumentException.class);

        ReflectionTestUtils.setField(n, "id", 50L);
        assertThatThrownBy(() -> n.dedupeKey("other")).isInstanceOf(IllegalStateException.class);

        NotificationRecipient r = new NotificationRecipient(n, 3L);
        assertThat(r.isUnread()).isTrue();
        r.dismiss(NOW.minusSeconds(5));   // 알림보다 이른 시각이 와도 알림 시각 이후로 맞춘다
        assertThat(r.getReadAt()).isEqualTo(NOW);
        assertThat(r.getDismissedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("알림 설정: 차단·아차사고 경고·승인 요청은 웹 알림을 끌 수 없다")
    void notificationPreference() {
        assertThatThrownBy(() -> new NotificationPreference(3L, NotificationType.BLOCKED, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        NotificationPreference p = new NotificationPreference(3L, NotificationType.AGENT_DONE, false, false);
        assertThat(p.isWebEnabled()).isFalse();
    }

    @Test
    @DisplayName("서명: 사람·작업자 중 하나, 해시는 소문자 64자, 내용 비교")
    void signature() {
        Signature s = Signature.byWorker(1L, 9L, SignatureTarget.TBM, 20L, SignaturePurpose.ATTENDEE, SHA, null, null);
        assertThat(s.getUserId()).isNull();
        assertThat(s.matches(SHA)).isTrue();
        assertThat(s.matches("b".repeat(64))).isFalse();
        assertThatThrownBy(() -> Signature.byUser(1L, 3L, SignatureTarget.PERMIT, 10L, SignaturePurpose.APPROVER,
                SHA.toUpperCase(), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Signature.byUser(1L, null, SignatureTarget.PERMIT, 10L, SignaturePurpose.APPROVER,
                SHA, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("양식: 판 번호 형식, 스키마는 객체, 켠 뒤에는 수정 불가 / 생성 문서: 다른 현장 전용 양식 금지")
    void formTemplateAndDocument() {
        assertThatThrownBy(() -> new FormTemplate(FormType.TBM, "1.0", null, "{}", "forms/tbm.html", 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FormTemplate(FormType.TBM, "v1", null, "[]", "forms/tbm.html", 3L))
                .isInstanceOf(IllegalArgumentException.class);

        FormTemplate t = new FormTemplate(FormType.HOT_WORK_PERMIT, "v1.2", 2L, "{\"type\":\"object\"}",
                "forms/hot.html", 3L);
        t.activate();
        assertThatThrownBy(() -> t.revise("{}", "forms/hot2.html")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(t::activate).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(t, "id", 7L);

        assertThatThrownBy(() -> GeneratedDocument.byUser(t, 1L, DocumentTarget.PERMIT, 10L, 0, 1, "pdf/a.pdf", SHA,
                1, "{}", 3L)).isInstanceOf(IllegalArgumentException.class);   // 현장 2 전용 양식
        assertThatThrownBy(() -> GeneratedDocument.byUser(t, 2L, DocumentTarget.PERMIT, 10L, 0, 0, "pdf/a.pdf", SHA,
                1, "{}", 3L)).isInstanceOf(IllegalArgumentException.class);   // seq 0
        GeneratedDocument d = GeneratedDocument.byAgent(t, 2L, DocumentTarget.PERMIT, 10L, 3, 1, "pdf/a.pdf", SHA,
                null, "{\"permitNo\":\"HOT-1\"}", 77L);
        assertThat(d.getFormType()).isEqualTo(FormType.HOT_WORK_PERMIT);
        assertThat(d.getCreatedByType()).isEqualTo(CreatorType.AGENT);
        assertThat(d.getAgentRunId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("임시저장: 기존 기록은 기준 판 필수, 옛 순번은 무시, 1MB 초과 거부, 저장할 때 판 비교")
    void formDraft() {
        FormDraft fresh = FormDraft.forNew(1L, 3L, DraftTarget.NEAR_MISS, "v1", "탱크 주변 미끄러짐", "{}", NOW);
        assertThat(fresh.getTargetId()).isNull();
        assertThat(fresh.getExpiresAt()).isEqualTo(NOW.plusDays(30));
        assertThat(fresh.isStale(5)).isFalse();

        FormDraft edit = FormDraft.forExisting(1L, 3L, DraftTarget.PERMIT, 10L, 2, "v1", "화기 허가", "{\"a\":1}", NOW);
        assertThat(edit.autosave(1, "화기 허가", "{\"a\":2}", NOW.plusSeconds(3))).isTrue();
        assertThat(edit.autosave(1, "화기 허가", "{\"a\":1}", NOW.plusSeconds(4))).isFalse();   // 늦게 온 옛 요청
        assertThat(edit.getDraftJson()).isEqualTo("{\"a\":2}");
        assertThat(edit.isStale(2)).isFalse();
        assertThat(edit.isStale(3)).isTrue();

        String big = "{\"x\":\"" + "가".repeat(FormDraft.MAX_BYTES / 3) + "\"}";
        assertThatThrownBy(() -> edit.autosave(2, "화기 허가", big, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormDraft.forNew(1L, 3L, DraftTarget.TBM, "v1", " ", "{}", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("비동기 작업: 시도 횟수 안에서 재시도, 다 쓰면 FAILED, PDF 완료는 결과 문서 필수")
    void asyncJob() {
        AsyncJob job = new AsyncJob(1L, 3L, JobType.EXPORT_PDF, JobTarget.PERMIT, 10L, 7,
                "{\"formType\":\"HOT_WORK_PERMIT\"}", "EXPORT_PDF:PERMIT:10:v7", 2, NOW);
        assertThatThrownBy(() -> job.report(10)).isInstanceOf(IllegalStateException.class);

        job.start("server-1", NOW);
        job.report(40);
        job.report(20);
        assertThat(job.getProgress()).isEqualTo(40);
        assertThat(job.fail("PDF 변환 오류", Duration.ofSeconds(30), NOW.plusSeconds(1))).isTrue();
        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.getRunAfter()).isEqualTo(NOW.plusSeconds(31));
        assertThat(job.getLockedBy()).isNull();

        job.start("server-2", NOW.plusSeconds(31));
        assertThatThrownBy(() -> job.succeed(null, NOW.plusSeconds(32))).isInstanceOf(IllegalArgumentException.class);
        assertThat(job.fail("PDF 변환 오류", Duration.ofSeconds(30), NOW.plusSeconds(33))).isFalse();
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getFinishedAt()).isNotNull();
        assertThatThrownBy(() -> job.cancel(NOW)).isInstanceOf(IllegalStateException.class);

        AsyncJob check = new AsyncJob(1L, 3L, JobType.RULE_CHECK, JobTarget.PERMIT, 10L, 0, null, "RULE_CHECK:10", 3, NOW);
        check.start("server-1", NOW);
        check.succeed(null, NOW.plusSeconds(1));
        assertThat(check.getProgress()).isEqualTo(100);
        assertThatThrownBy(() -> new AsyncJob(1L, 3L, JobType.RULE_CHECK, JobTarget.PERMIT, 10L, 0, null, "k", 11, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
