package com.example.backend.approval;

import com.example.backend.approval.entity.*;
import com.example.backend.approval.repository.ApprovalPolicyRepository;
import com.example.backend.approval.repository.ApprovalRepository;
import com.example.backend.approval.repository.ApprovalStepRepository;
import com.example.backend.dailylog.entity.DailyLogStatus;
import com.example.backend.dailylog.entity.DailySafetyLog;
import com.example.backend.dailylog.repository.DailySafetyLogRepository;
import com.example.backend.form.entity.*;
import com.example.backend.form.repository.FormDraftRepository;
import com.example.backend.form.repository.FormTemplateRepository;
import com.example.backend.form.repository.GeneratedDocumentRepository;
import com.example.backend.history.entity.*;
import com.example.backend.history.repository.RecordAccessLogRepository;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.job.entity.AsyncJob;
import com.example.backend.job.entity.JobStatus;
import com.example.backend.job.entity.JobTarget;
import com.example.backend.job.entity.JobType;
import com.example.backend.job.repository.AsyncJobRepository;
import com.example.backend.notification.entity.*;
import com.example.backend.notification.repository.NotificationPreferenceRepository;
import com.example.backend.notification.repository.NotificationRecipientRepository;
import com.example.backend.notification.repository.NotificationRepository;
import com.example.backend.signature.entity.Signature;
import com.example.backend.signature.entity.SignaturePurpose;
import com.example.backend.signature.entity.SignatureTarget;
import com.example.backend.signature.repository.SignatureRepository;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.worker.entity.Worker;
import com.example.backend.worker.entity.WorkerSite;
import com.example.backend.worker.repository.WorkerRepository;
import com.example.backend.worker.repository.WorkerSiteRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 결재·이력·안전일지·알림·서명·양식·임시저장·비동기 작업 엔티티를 실제 로컬 DB로 확인 (끝나면 롤백).
 * V6 의 CHECK·외래키·유니크(생성 컬럼 포함), V9 의 잠금 트리거(결정 후 변경 불가, 추가만 가능한 테이블)를 본다.
 */
@SpringBootTest
@Transactional
@DisplayName("결재·이력 엔티티 DB 통합 (V6)")
class ApprovalHistoryEntitiesDbTest {

    /** 실제 시각보다 과거로 둔다 (DB 기본값 시각과 비교하는 제약이 있어서) */
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 9, 0);
    private static final LocalDate D = T0.toLocalDate();
    private static final String SHA = "0123456789abcdef".repeat(4);

    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired WorkerRepository workerRepository;
    @Autowired WorkerSiteRepository workerSiteRepository;
    @Autowired ApprovalRepository approvalRepository;
    @Autowired ApprovalStepRepository stepRepository;
    @Autowired ApprovalPolicyRepository policyRepository;
    @Autowired RecordHistoryRepository historyRepository;
    @Autowired RecordAccessLogRepository accessLogRepository;
    @Autowired DailySafetyLogRepository dailyLogRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired NotificationRecipientRepository recipientRepository;
    @Autowired NotificationPreferenceRepository preferenceRepository;
    @Autowired SignatureRepository signatureRepository;
    @Autowired FormTemplateRepository templateRepository;
    @Autowired GeneratedDocumentRepository documentRepository;
    @Autowired FormDraftRepository draftRepository;
    @Autowired AsyncJobRepository jobRepository;
    @Autowired EntityManager em;

    private String tag;
    private Long userId;
    private Long approverId;
    private Long siteId;
    private Long workerId;
    /** 결재 대상 id. 다른 데이터의 대기 중 결재와 겹치지 않게 큰 난수 */
    private Long targetId;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        userId = userRepository.save(User.bootstrapAdmin("b4w-" + tag + "@example.com", "HASH", T0)).getId();
        approverId = userRepository.save(User.bootstrapAdmin("b4a-" + tag + "@example.com", "HASH", T0)).getId();
        siteId = siteRepository.save(new Site("테스트현장-" + tag, SiteMode.VERIFY)).getId();
        workerId = workerRepository.save(new Worker(null, "김작업", null, "용접", null)).getId();
        workerSiteRepository.save(new WorkerSite(workerId, siteId, D.minusDays(10)));
        targetId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
        em.flush();
    }

    private Approval newApproval() {
        return approvalRepository.saveAndFlush(new Approval(siteId, ApprovalTarget.PERMIT, targetId, userId,
                T0.plusHours(8), T0));
    }

    private FormTemplate activeTemplate() {
        FormTemplate t = new FormTemplate(FormType.HOT_WORK_PERMIT, "v1", siteId, "{\"type\":\"object\"}",
                "forms/hot-v1.html", userId);
        t.activate();
        return templateRepository.saveAndFlush(t);
    }

    private void nativeUpdate(String sql, Object id) {
        em.createNativeQuery(sql).setParameter(1, id).executeUpdate();
    }

    @Test
    @DisplayName("2단계 결재 흐름과 결재선 정책이 제약을 통과해 저장되고 다시 읽힌다")
    void approvalFlow() {
        policyRepository.save(new ApprovalPolicy(siteId, ApprovalTarget.PERMIT, 1, "부서장 확인", ApproverRole.APPROVER, userId));
        policyRepository.save(new ApprovalPolicy(siteId, ApprovalTarget.PERMIT, 2, "안전관리자 승인", ApproverRole.SITE_ADMIN, userId));

        Approval a = newApproval();
        ApprovalStep s1 = stepRepository.save(new ApprovalStep(a, 1, "부서장 확인", ApproverRole.APPROVER, approverId));
        ApprovalStep s2 = stepRepository.save(new ApprovalStep(a, 2, "안전관리자 승인", ApproverRole.SITE_ADMIN, null));
        em.flush();

        // 1단계 승인 → flush (PENDING 단계는 하나만) → 2단계 열기
        s1.approve(approverId, null, T0.plusMinutes(10));
        em.flush();
        s2.open();
        a.moveTo(2);
        em.flush();
        s2.approve(approverId, "확인", T0.plusMinutes(20));
        a.approve(approverId, null, T0.plusMinutes(20));
        em.flush();
        em.clear();

        Approval saved = approvalRepository.findById(a.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(saved.getCurrentStep()).isEqualTo(2);
        assertThat(saved.getApproverId()).isEqualTo(approverId);
        assertThat(saved.getLockVersion()).isPositive();
        assertThat(stepRepository.findByApprovalIdOrderByStepNoAsc(a.getId()))
                .extracting(ApprovalStep::getStatus)
                .containsExactly(ApprovalStepStatus.APPROVED, ApprovalStepStatus.APPROVED);
        assertThat(policyRepository.findBySiteIdAndTargetTypeOrderByStepNoAsc(siteId, ApprovalTarget.PERMIT))
                .extracting(ApprovalPolicy::getRequiredRole)
                .containsExactly(ApproverRole.APPROVER, ApproverRole.SITE_ADMIN);
        assertThat(approvalRepository.findByTargetTypeAndTargetIdAndStatus(ApprovalTarget.PERMIT, targetId,
                ApprovalStatus.PENDING)).isEmpty();
    }

    @Test
    @DisplayName("같은 기록에 대기 중 결재는 하나만 (uk_appr_pending), 결정 후에는 새 요청 가능")
    void onePendingApprovalPerTarget() {
        Approval first = newApproval();
        first.reject(approverId, "측정값 누락", T0.plusMinutes(5));
        em.flush();

        Approval second = newApproval();   // 앞 결재가 결정됐으니 새 요청 가능
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(approvalRepository.findByTargetTypeAndTargetIdAndStatus(ApprovalTarget.PERMIT, targetId,
                ApprovalStatus.PENDING)).get().extracting(Approval::getId).isEqualTo(second.getId());

        assertThatThrownBy(this::newApproval).isInstanceOf(RuntimeException.class);   // 대기 중이 이미 있음
    }

    /** 반려된 결재 + 반려된 1단계 */
    private ApprovalStep rejectedApprovalWithStep() {
        Approval a = newApproval();
        ApprovalStep s = stepRepository.save(new ApprovalStep(a, 1, "확인", ApproverRole.APPROVER, null));
        s.reject(approverId, "보완 필요", T0.plusMinutes(1));
        a.reject(approverId, "보완 필요", T0.plusMinutes(1));
        em.flush();
        return s;
    }

    @Test
    @DisplayName("결정된 결재는 바꿀 수 없다 (trg_appr_final)")
    void decidedApprovalImmutable() {
        Long approvalId = rejectedApprovalWithStep().getApprovalId();
        assertThatThrownBy(() -> nativeUpdate("UPDATE approvals SET status = 'APPROVED' WHERE id = ?1", approvalId))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("결정된 결재 단계는 바꿀 수 없다 (trg_aps_final)")
    void decidedStepImmutable() {
        Long stepId = rejectedApprovalWithStep().getId();
        assertThatThrownBy(() -> nativeUpdate("UPDATE approval_steps SET comment = '변경' WHERE id = ?1", stepId))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("이력은 저장되고 다시 읽히며, 수정할 수 없다 (trg_hist_no_update)")
    void historyAppendOnly() {
        RecordHistory h = historyRepository.save(RecordHistory.byUser(siteId, HistoryTarget.PERMIT, targetId,
                HistoryAction.SUBMIT, userId, "{\"status\":\"DRAFT\"}", "{\"status\":\"PENDING\"}"));
        historyRepository.save(RecordHistory.byAgent(siteId, HistoryTarget.PERMIT, targetId, HistoryAction.AI_DRAFT,
                userId, null, "{\"status\":\"DRAFT\"}"));   // agent_runs 외래키는 없어 아무 id 로 확인
        em.flush();
        assertThat(historyRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAscIdAsc(HistoryTarget.PERMIT, targetId))
                .extracting(RecordHistory::getAction).containsExactly(HistoryAction.SUBMIT, HistoryAction.AI_DRAFT);

        assertThatThrownBy(() -> nativeUpdate("UPDATE record_history SET action = 'UPDATE' WHERE id = ?1", h.getId()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("이력은 지울 수 없다 (trg_hist_no_delete)")
    void historyNoDelete() {
        RecordHistory h = historyRepository.saveAndFlush(RecordHistory.bySystem(siteId, HistoryTarget.PERMIT, targetId,
                HistoryAction.STATUS_CHANGE, null, "{\"status\":\"EXPIRED\"}"));
        assertThatThrownBy(() -> nativeUpdate("DELETE FROM record_history WHERE id = ?1", h.getId()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("열람 기록은 지울 수 없다 (trg_ral_no_delete)")
    void accessLogNoDelete() {
        RecordAccessLog log = accessLogRepository.saveAndFlush(new RecordAccessLog(userId, siteId,
                AccessResource.WORKER_PII, workerId, AccessAction.VIEW, null, "10.0.0.5"));
        assertThat(accessLogRepository.findBySiteIdOrderByCreatedAtDescIdDesc(siteId, PageRequest.of(0, 10)))
                .extracting(RecordAccessLog::getResourceType).containsExactly(AccessResource.WORKER_PII);
        assertThatThrownBy(() -> nativeUpdate("DELETE FROM record_access_logs WHERE id = ?1", log.getId()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("서명은 바꿀 수 없다 (trg_sig_no_update)")
    void signatureNoUpdate() {
        Signature sig = signatureRepository.saveAndFlush(Signature.byUser(siteId, userId, SignatureTarget.PERMIT,
                targetId, SignaturePurpose.WRITER, SHA, null, null));
        assertThat(signatureRepository.existsByTargetTypeAndTargetIdAndPurposeAndUserId(SignatureTarget.PERMIT,
                targetId, SignaturePurpose.WRITER, userId)).isTrue();
        assertThatThrownBy(() -> nativeUpdate("UPDATE signatures SET ip = '1.1.1.1' WHERE id = ?1", sig.getId()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("생성 문서는 지울 수 없다 (trg_gd_no_delete), 다음 출력 순번은 최대 + 1")
    void generatedDocumentNoDelete() {
        GeneratedDocument doc = documentRepository.saveAndFlush(GeneratedDocument.byUser(activeTemplate(), siteId,
                DocumentTarget.PERMIT, targetId, 0, 1, "pdf/" + tag + "-1.pdf", SHA, 2,
                "{\"permitNo\":\"HOT-1\"}", userId));
        assertThat(documentRepository.findMaxSeq(DocumentTarget.PERMIT, targetId, FormType.HOT_WORK_PERMIT)).isEqualTo(1);
        assertThatThrownBy(() -> nativeUpdate("DELETE FROM generated_documents WHERE id = ?1", doc.getId()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("서명: 같은 사람이 같은 자격으로 두 번 서명 금지 (uk_sig_once)")
    void signatureOnce() {
        signatureRepository.save(Signature.byWorker(siteId, workerId, SignatureTarget.TBM, targetId,
                SignaturePurpose.ATTENDEE, SHA, null, null));
        em.flush();
        assertThat(signatureRepository.existsByTargetTypeAndTargetIdAndPurposeAndWorkerId(SignatureTarget.TBM, targetId,
                SignaturePurpose.ATTENDEE, workerId)).isTrue();

        assertThatThrownBy(() -> {
            signatureRepository.save(Signature.byWorker(siteId, workerId, SignatureTarget.TBM, targetId,
                    SignaturePurpose.ATTENDEE, SHA, null, null));
            em.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("그 현장에 배정되지 않은 작업자는 서명할 수 없다 (fk_sig_worker)")
    void unassignedWorkerCannotSign() {
        Long otherSite = siteRepository.save(new Site("다른현장-" + tag, SiteMode.VERIFY)).getId();
        assertThatThrownBy(() -> {
            signatureRepository.save(Signature.byWorker(otherSite, workerId, SignatureTarget.TBM, targetId,
                    SignaturePurpose.ATTENDEE, SHA, null, null));
            em.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    private DailySafetyLog approvedDailyLog() {
        DailySafetyLog log = DailySafetyLog.byUser(siteId, D, userId);
        log.update("화기 2건, 차단 1건", "{\"works\":2}", userId);
        log.submit();
        log.approve();
        return dailyLogRepository.saveAndFlush(log);
    }

    @Test
    @DisplayName("승인된 안전일지는 내용을 고칠 수 없다 (trg_dsl_lock)")
    void approvedDailyLogLocked() {
        DailySafetyLog log = approvedDailyLog();
        assertThat(dailyLogRepository.findBySiteIdAndLogDate(siteId, D)).get()
                .extracting(DailySafetyLog::getStatus).isEqualTo(DailyLogStatus.APPROVED);
        assertThatThrownBy(() -> nativeUpdate("UPDATE daily_safety_logs SET summary = '변경' WHERE id = ?1", log.getId()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("안전일지는 현장·날짜마다 하나 (uk_dsl_site_date)")
    void oneDailyLogPerDay() {
        approvedDailyLog();
        assertThatThrownBy(() -> {
            dailyLogRepository.save(DailySafetyLog.byUser(siteId, D, userId));
            em.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("알림: 수신함·배지·모두 읽음, 같은 사람 중복 금지, 필수 알림은 끌 수 없음 (ck_np_mandatory)")
    void notifications() {
        Notification n = notificationRepository.save(Notification.of(siteId, NotificationType.APPROVAL_REQUEST,
                NotificationSeverity.WARN, "승인 요청", "화기작업 허가 승인 요청이 있습니다.", T0)
                .about(NotificationTarget.PERMIT, targetId).by(ActorType.USER, userId)
                .dedupeKey("APPROVAL_REQUEST:" + tag).expiresAt(T0.plusDays(1)));
        recipientRepository.save(new NotificationRecipient(n, approverId));
        NotificationRecipient mine = recipientRepository.save(new NotificationRecipient(n, userId));
        mine.dismiss(T0.plusMinutes(1));
        preferenceRepository.save(new NotificationPreference(approverId, NotificationType.AGENT_DONE, false, false));
        em.flush();

        assertThat(notificationRepository.findByDedupeKey("APPROVAL_REQUEST:" + tag)).isPresent();
        assertThat(recipientRepository.countByUserIdAndReadAtIsNullAndDismissedAtIsNull(approverId)).isEqualTo(1);
        assertThat(recipientRepository.findByUserIdAndDismissedAtIsNullOrderByIdDesc(userId, PageRequest.of(0, 20))).isEmpty();
        assertThat(recipientRepository.markAllRead(approverId, LocalDateTime.now())).isEqualTo(1);
        em.clear();
        assertThat(recipientRepository.countByUserIdAndReadAtIsNullAndDismissedAtIsNull(approverId)).isZero();
        assertThat(preferenceRepository.findByUserId(approverId)).extracting(NotificationPreference::isWebEnabled)
                .containsExactly(false);

        assertThatThrownBy(() -> {
            recipientRepository.save(new NotificationRecipient(n, approverId));
            em.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("필수 알림 끄기는 DB도 막는다 (ck_np_mandatory)")
    void mandatoryPreferenceInDb() {
        assertThatThrownBy(() -> em.createNativeQuery(
                        "INSERT INTO notification_preferences (user_id, type, web_enabled) VALUES (?1, 'BLOCKED', FALSE)")
                .setParameter(1, userId).executeUpdate()).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("양식: 같은 범위에 켜진 판은 하나 (uk_ft_active), 옛 판을 내리면 새 판을 켤 수 있다")
    void oneActiveTemplatePerScope() {
        FormTemplate v1 = activeTemplate();
        FormTemplate v2 = templateRepository.saveAndFlush(new FormTemplate(FormType.HOT_WORK_PERMIT, "v2", siteId,
                "{\"type\":\"object\"}", "forms/hot-v2.html", userId));

        // 옛 판을 먼저 내리고 flush → 새 판 켜기
        v1.retire();
        em.flush();
        v2.activate();
        em.flush();
        assertThat(templateRepository.findByFormTypeAndSiteIdAndStatus(FormType.HOT_WORK_PERMIT, siteId,
                FormTemplateStatus.ACTIVE)).get().extracting(FormTemplate::getVersion).isEqualTo("v2");

        // 켜진 판이 있는데 또 켜면 DB가 막는다
        FormTemplate v3 = templateRepository.saveAndFlush(new FormTemplate(FormType.HOT_WORK_PERMIT, "v3", siteId,
                "{\"type\":\"object\"}", "forms/hot-v3.html", userId));
        v3.activate();
        assertThatThrownBy(() -> em.flush()).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("임시저장: 조건부 자동 저장(옛 순번 무시), 기존 기록은 사람당 1개 (uk_fd_existing)")
    void formDrafts() {
        FormDraft draft = draftRepository.saveAndFlush(FormDraft.forExisting(siteId, userId, DraftTarget.PERMIT,
                targetId, 3, "v1", "R-201 화기 허가", "{\"lel\":3}", T0));
        draftRepository.saveAndFlush(FormDraft.forNew(siteId, userId, DraftTarget.NEAR_MISS, "v1", "보고 1", "{}", T0));
        draftRepository.saveAndFlush(FormDraft.forNew(siteId, userId, DraftTarget.NEAR_MISS, "v1", "보고 2", "{}", T0));

        LocalDateTime later = T0.plusDays(30).plusSeconds(5);
        assertThat(draftRepository.autosave(draft.getId(), userId, 2, "R-201 화기 허가", "{\"lel\":4}", later)).isEqualTo(1);
        assertThat(draftRepository.autosave(draft.getId(), userId, 1, "R-201 화기 허가", "{\"lel\":3}", later)).isZero();
        assertThat(draftRepository.autosave(draft.getId(), approverId, 3, "남의 것", "{}", later)).isZero();
        em.clear();
        FormDraft saved = draftRepository.findByUserIdAndTargetTypeAndTargetId(userId, DraftTarget.PERMIT, targetId)
                .orElseThrow();
        assertThat(saved.getClientSeq()).isEqualTo(2);
        assertThat(saved.getDraftJson()).contains("4");
        assertThat(draftRepository.findByUserIdOrderByUpdatedAtDesc(userId)).hasSize(3);

        assertThatThrownBy(() -> draftRepository.saveAndFlush(FormDraft.forExisting(siteId, userId, DraftTarget.PERMIT,
                targetId, 3, "v1", "중복", "{}", T0))).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("비동기 작업: 중복 방지 키 하나, 가져가기(SKIP LOCKED)는 시각이 된 대기 작업만, 결과 문서 연결")
    void asyncJobs() {
        String key = "EXPORT_PDF:PERMIT:" + targetId + ":v0:" + tag;
        jobRepository.saveAndFlush(new AsyncJob(siteId, userId, JobType.EXPORT_PDF, JobTarget.PERMIT,
                targetId, 0, "{\"formType\":\"HOT_WORK_PERMIT\"}", key, 3, T0));
        em.clear();

        AsyncJob claimed = jobRepository.claimNext(LocalDateTime.now()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(claimed.getRunAfter()).isBeforeOrEqualTo(LocalDateTime.now());

        AsyncJob mine = jobRepository.findByIdempotencyKey(key).orElseThrow();
        mine.start("test-server", T0.plusSeconds(1));
        mine.report(50);
        GeneratedDocument doc = documentRepository.save(GeneratedDocument.byUser(activeTemplate(), siteId,
                DocumentTarget.PERMIT, targetId, 0, 1, "pdf/" + tag + "-job.pdf", SHA, 1, "{}", userId));
        em.flush();
        mine.succeed(doc.getId(), T0.plusSeconds(5));
        em.flush();
        em.clear();

        AsyncJob done = jobRepository.findById(mine.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(done.getProgress()).isEqualTo(100);
        assertThat(done.getAttempts()).isEqualTo(1);
        assertThat(done.getResultDocumentId()).isEqualTo(doc.getId());
        assertThat(jobRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(JobTarget.PERMIT, targetId)).hasSize(1);

        // [PDF 출력]을 두 번 눌러도 작업은 하나 (uk_job_idem)
        assertThatThrownBy(() -> jobRepository.saveAndFlush(new AsyncJob(siteId, userId, JobType.EXPORT_PDF,
                JobTarget.PERMIT, targetId, 0, null, key, 3, T0))).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("비동기 작업: 실패 후 재시도는 run_after 가 미래라 바로 가져가지 않는다")
    void retryDelaysClaim() {
        AsyncJob job = jobRepository.saveAndFlush(new AsyncJob(siteId, userId, JobType.RULE_CHECK, JobTarget.PERMIT,
                targetId, 0, null, "RULE_CHECK:" + tag, 3, T0));
        job.start("test-server", T0);
        job.fail("내부 오류", Duration.ofDays(3650), T0.plusSeconds(1));   // 아주 먼 미래로 미룸
        em.flush();
        em.clear();

        List<Long> claimable = jobRepository.claimNext(LocalDateTime.now()).map(j -> List.of(j.getId())).orElse(List.of());
        assertThat(claimable).doesNotContain(job.getId());
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getErrorMessage()).isEqualTo("내부 오류");
    }
}
