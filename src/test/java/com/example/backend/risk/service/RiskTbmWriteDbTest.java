package com.example.backend.risk.service;

import com.example.backend.approval.entity.ApprovalStatus;
import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.repository.ApprovalRepository;
import com.example.backend.approval.service.ApprovalService;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.risk.dto.RiskAssessmentResponse;
import com.example.backend.risk.dto.SaveRiskAssessmentRequest;
import com.example.backend.risk.entity.AssessmentType;
import com.example.backend.risk.entity.RiskLevel;
import com.example.backend.risk.entity.RiskStatus;
import com.example.backend.rule.entity.WorkType;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.tbm.dto.CompleteTbmRequest;
import com.example.backend.tbm.dto.SaveTbmRequest;
import com.example.backend.tbm.dto.TbmResponse;
import com.example.backend.tbm.entity.TbmStatus;
import com.example.backend.tbm.service.TbmCommandService;
import com.example.backend.tbm.service.TbmService;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.work.dto.CreateWorkRequest;
import com.example.backend.work.service.WorkService;
import com.example.backend.worker.entity.EmploymentType;
import com.example.backend.worker.entity.Worker;
import com.example.backend.worker.entity.WorkerSite;
import com.example.backend.worker.repository.WorkerRepository;
import com.example.backend.worker.repository.WorkerSiteRepository;
import com.example.backend.tbm.dto.SaveTbmAttendeesRequest;
import com.example.backend.worker.dto.SiteWorkerResponse;
import com.example.backend.worker.service.SiteWorkerService;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.example.backend.support.TestFixtures.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

/** 사람이 작성하는 위험성평가·TBM: 작성·수정·새 판·실시 완료·확인 (실제 DB, 끝나면 롤백) */
@SpringBootTest
@Transactional
@DisplayName("위험성평가·TBM 작성 (실제 DB)")
class RiskTbmWriteDbTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);

    @Autowired RiskAssessmentService riskService;
    @Autowired TbmCommandService tbmService;
    @Autowired TbmService tbmQuery;
    @Autowired ApprovalService approvalService;
    @Autowired ApprovalRepository approvalRepository;
    @Autowired WorkService workService;
    @Autowired RecordHistoryRepository historyRepository;
    @Autowired WorkerRepository workerRepository;
    @Autowired WorkerSiteRepository workerSiteRepository;
    @Autowired UserRepository userRepository;
    @Autowired SiteWorkerService siteWorkerService;
    @Autowired JsonMapper jsonMapper;
    @Autowired SiteRepository siteRepository;

    private Long userId;
    private Long siteId;
    private Long workId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        userId = userRepository.save(User.bootstrapAdmin("write-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        siteId = siteRepository.save(new Site("작성현장-" + tag, SiteMode.GUIDE)).getId();   // 안내 모드: 자기 승인 가능
        workId = work(DAY);
    }

    private Long work(LocalDate date) {
        return workService.create(userId, new CreateWorkRequest(siteId, date, "R-201 배관 교체", null, WorkType.HOT,
                date.atTime(13, 0), date.atTime(15, 0), 3)).workId();
    }

    private static SaveRiskAssessmentRequest.Item item(String hazard, String accident, int f, int s) {
        return item(hazard, accident, f, s, null);
    }

    private static SaveRiskAssessmentRequest.Item item(String hazard, String accident, int f, int s, RiskLevel level) {
        return new SaveRiskAssessmentRequest.Item(99, "절단", hazard, accident, f, s, level, "가연성가스 측정\n불티 비산방지포",
                null, null);
    }

    /** 웹 모양: siteId·workId·DAILY, formData 는 JSON 문자열 */
    private SaveRiskAssessmentRequest risk(Integer lockVersion, SaveRiskAssessmentRequest.Item... items) {
        return new SaveRiskAssessmentRequest(siteId, workId, AssessmentType.DAILY, DAY,
                jsonMapper.valueToTree("{\"workSummary\":\"배관 교체\"}"), List.of(items), lockVersion);
    }

    private Long worker(boolean assigned) {
        Long id = workerRepository.save(new Worker(null, "박작업" + UUID.randomUUID().toString().substring(0, 4), null,
                "배관공", EmploymentType.DIRECT)).getId();
        if (assigned) {
            workerSiteRepository.save(new WorkerSite(id, siteId, DAY.minusDays(30)));
        }
        return id;
    }

    private SaveTbmRequest tbm(LocalDate date, List<Long> works, List<Long> workers, Integer lockVersion) {
        return new SaveTbmRequest(date, null, null, "이반장", List.of("가스측정 결과 확인", " ", "화재감시자 지정"), null, works,
                workers.stream().map(w -> new SaveTbmRequest.Attendee(w, true)).toList(), lockVersion);
    }

    private Long pendingApproval(ApprovalTarget type, Long id) {
        return approvalRepository.findByTargetTypeAndTargetIdAndStatus(type, id, ApprovalStatus.PENDING).orElseThrow().getId();
    }

    // ------------------------------------------------------------------ 위험성평가

    @Test
    @DisplayName("작성: 1판 작성 중, 순번은 보낸 순서, 등급은 빈도×강도(yaml), 이력 CREATE. 두 번째 작성은 409")
    void createRisk() {
        RiskAssessmentResponse r = riskService.create(userId, risk(null,
                item("불티 착화", "FIRE", 3, 4), item("베임", null, 1, 2)));

        assertThat(r.status()).isEqualTo(RiskStatus.DRAFT);
        assertThat(r.revision()).isEqualTo(1);
        assertThat(r.items()).extracting(RiskAssessmentResponse.Item::seq, RiskAssessmentResponse.Item::riskLevel)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, RiskLevel.HIGH),
                        org.assertj.core.groups.Tuple.tuple(2, RiskLevel.LOW));
        assertThat(r.canSubmit()).isTrue();
        assertThat(historyRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAscIdAsc(HistoryTarget.RISK, r.riskAssessmentId()))
                .extracting(h -> h.getAction()).containsExactly(HistoryAction.CREATE);
        expectCode(() -> riskService.create(userId, risk(null, item("x", null, 1, 1))), ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("수정: lockVersion 이 다르면 409, 맞으면 위험요인을 통째로 바꾼다. 모르는 발생형태·개선 후 값 한쪽만은 400")
    void updateRisk() {
        RiskAssessmentResponse r = riskService.create(userId, risk(null,
                item("a", "FIRE", 2, 2), item("b", null, 2, 2), item("c", null, 2, 2)));

        expectCode(() -> riskService.update(userId, r.riskAssessmentId(), risk(r.lockVersion() + 1, item("x", null, 1, 1))),
                ErrorCode.CONFLICT_MODIFIED);
        RiskAssessmentResponse updated = riskService.update(userId, r.riskAssessmentId(),
                risk(r.lockVersion(), item("새 위험요인", "FALL", 4, 4)));
        assertThat(updated.items()).singleElement().satisfies(i -> {
            assertThat(i.seq()).isEqualTo(1);
            assertThat(i.hazard()).isEqualTo("새 위험요인");
            assertThat(i.accidentType()).isEqualTo("FALL");
        });

        expectCode(() -> riskService.update(userId, r.riskAssessmentId(), risk(updated.lockVersion(),
                item("x", "NO_SUCH_CODE", 1, 1))), ErrorCode.INVALID_REQUEST);
        // 위 실패 요청은 실제로는 롤백되지만 이 테스트는 한 트랜잭션이라 버전이 올라가 있을 수 있다 → 최신 버전으로
        int latest = riskService.get(userId, r.riskAssessmentId()).lockVersion();
        expectCode(() -> riskService.update(userId, r.riskAssessmentId(), risk(latest,
                new SaveRiskAssessmentRequest.Item(1, "절단", "x", null, 2, 2, null, "대책", 1, null))), ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("승인 요청 후에는 못 고치고, 반려되면 새 판(2판, 위험요인 복사)을 만들고 이전 판은 SUPERSEDED")
    void rejectThenRevise() {
        RiskAssessmentResponse r = riskService.create(userId, risk(null, item("불티 착화", "FIRE", 3, 4)));
        riskService.submit(userId, r.riskAssessmentId());
        expectCode(() -> riskService.update(userId, r.riskAssessmentId(), risk(r.lockVersion(), item("x", null, 1, 1))),
                ErrorCode.INVALID_STATE);
        expectCode(() -> riskService.revise(userId, r.riskAssessmentId()), ErrorCode.INVALID_STATE);   // 아직 반려 전

        approvalService.reject(userId, pendingApproval(ApprovalTarget.RISK, r.riskAssessmentId()), "대책 보강", List.of("감시자"));
        RiskAssessmentResponse next = riskService.revise(userId, r.riskAssessmentId());

        assertThat(next.revision()).isEqualTo(2);
        assertThat(next.status()).isEqualTo(RiskStatus.DRAFT);
        assertThat(next.items()).extracting(RiskAssessmentResponse.Item::hazard).containsExactly("불티 착화");
        assertThat(riskService.get(userId, r.riskAssessmentId()).status()).isEqualTo(RiskStatus.SUPERSEDED);
        assertThat(riskService.getByWork(userId, workId).riskAssessmentId()).isEqualTo(next.riskAssessmentId());
        expectCode(() -> riskService.revise(userId, r.riskAssessmentId()), ErrorCode.INVALID_STATE);   // 이미 대체됨
    }

    // ------------------------------------------------------------------ TBM

    @Test
    @DisplayName("TBM 작성: 같은 날 작업·배치된 작업자 연결, 교육 요점은 빈칸 빼고 저장")
    void createTbm() {
        Long w = worker(true);
        TbmResponse t = tbmService.create(userId, siteId, tbm(DAY, List.of(workId, workId), List.of(w), null));

        assertThat(t.status()).isEqualTo(TbmStatus.DRAFT);
        assertThat(t.points()).containsExactly("가스측정 결과 확인", "화재감시자 지정");
        assertThat(t.works()).extracting(TbmResponse.WorkRef::workId).containsExactly(workId);
        assertThat(t.attendees()).extracting(TbmResponse.Attendee::workerId).containsExactly(w);
    }

    @Test
    @DisplayName("TBM 작성 거부: 다른 날 작업, 배치 안 된 작업자, 그날 다른 TBM 에 이미 들어간 작업")
    void tbmRejects() {
        Long otherDay = work(DAY.plusDays(1));
        expectCode(() -> tbmService.create(userId, siteId, tbm(DAY, List.of(otherDay), List.of(), null)), ErrorCode.INVALID_REQUEST);
        Long outsider = worker(false);
        expectCode(() -> tbmService.create(userId, siteId, tbm(DAY, List.of(), List.of(outsider), null)), ErrorCode.INVALID_REQUEST);

        tbmService.create(userId, siteId, tbm(DAY, List.of(workId), List.of(), null));
        expectCode(() -> tbmService.create(userId, siteId, tbm(DAY, List.of(workId), List.of(), null)), ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("TBM 수정: lockVersion 확인, 작업·참석자 통째로 바꿈 (날짜 변경 포함)")
    void updateTbm() {
        Long w1 = worker(true);
        Long w2 = worker(true);
        TbmResponse t = tbmService.create(userId, siteId, tbm(DAY, List.of(workId), List.of(w1), null));

        expectCode(() -> tbmService.update(userId, t.tbmId(), tbm(DAY, List.of(), List.of(), t.lockVersion() + 1)),
                ErrorCode.CONFLICT_MODIFIED);
        Long nextDayWork = work(DAY.plusDays(1));
        TbmResponse moved = tbmService.update(userId, t.tbmId(), tbm(DAY.plusDays(1), List.of(nextDayWork), List.of(w2),
                t.lockVersion()));

        assertThat(moved.tbmDate()).isEqualTo(DAY.plusDays(1));
        assertThat(moved.works()).extracting(TbmResponse.WorkRef::workId).containsExactly(nextDayWork);
        assertThat(moved.attendees()).extracting(TbmResponse.Attendee::workerId).containsExactly(w2);
    }

    @Test
    @DisplayName("실시 완료 → 확인 요청(대기 중 수정 불가) → 확인 APPROVED(이후 수정 불가)")
    void completeSubmitApprove() {
        Long w = worker(true);
        TbmResponse t = tbmService.create(userId, siteId, tbm(DAY, List.of(workId), List.of(w), null));
        expectCode(() -> tbmService.submit(userId, t.tbmId()), ErrorCode.INVALID_STATE);   // 실시 전
        expectCode(() -> tbmService.complete(userId, t.tbmId(), new CompleteTbmRequest(t.lockVersion(), DAY.plusDays(1).atTime(8, 0), null)),
                ErrorCode.INVALID_REQUEST);   // 다른 날

        TbmResponse done = tbmService.complete(userId, t.tbmId(), new CompleteTbmRequest(t.lockVersion(), DAY.atTime(8, 30), null));
        assertThat(done.status()).isEqualTo(TbmStatus.DONE);
        assertThat(done.attendeeCount()).isEqualTo(1);

        tbmService.submit(userId, t.tbmId());
        expectCode(() -> tbmService.update(userId, t.tbmId(), tbm(DAY, List.of(), List.of(), done.lockVersion())),
                ErrorCode.INVALID_STATE);

        approvalService.approve(userId, pendingApproval(ApprovalTarget.TBM, t.tbmId()), null);
        expectCode(() -> tbmService.reopen(userId, t.tbmId()), ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("확인이 반려되면 작성 중으로 돌아가 다시 고칠 수 있다")
    void rejectedTbm_backToDraft() {
        TbmResponse t = tbmService.create(userId, siteId, tbm(DAY, List.of(workId), List.of(worker(true)), null));
        tbmService.complete(userId, t.tbmId(), new CompleteTbmRequest(t.lockVersion(), DAY.atTime(8, 30), null));
        tbmService.submit(userId, t.tbmId());

        approvalService.reject(userId, pendingApproval(ApprovalTarget.TBM, t.tbmId()), "사진 없음", null);

        TbmResponse back = tbmQuery.get(userId, t.tbmId());
        assertThat(back.status()).isEqualTo(TbmStatus.DRAFT);
        TbmResponse fixed = tbmService.update(userId, t.tbmId(), tbm(DAY, List.of(workId), List.of(), back.lockVersion()));
        assertThat(fixed.attendees()).isEmpty();
    }

    // ------------------------------------------------------------------ 웹 명세 맞춤 (11/02)

    @Test
    @DisplayName("등급 C-2: 계산보다 높게 고르면 그 값, 낮거나 비우면 계산 등급. computedLevel 은 늘 계산 등급")
    void riskLevel_c2() {
        RiskAssessmentResponse r = riskService.create(userId, risk(null,
                item("높게 고름", null, 1, 2, RiskLevel.HIGH),      // 계산 LOW → HIGH 유지
                item("낮게 고름", null, 3, 4, RiskLevel.MEDIUM),    // 계산 HIGH → HIGH 로
                item("안 고름", null, 2, 3, null)));               // 계산 MEDIUM

        assertThat(r.items()).extracting(RiskAssessmentResponse.Item::riskLevel, RiskAssessmentResponse.Item::computedLevel)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(RiskLevel.HIGH, RiskLevel.LOW),
                        org.assertj.core.groups.Tuple.tuple(RiskLevel.HIGH, RiskLevel.HIGH),
                        org.assertj.core.groups.Tuple.tuple(RiskLevel.MEDIUM, RiskLevel.MEDIUM));
        assertThat(r.items()).extracting(RiskAssessmentResponse.Item::seq).containsExactly(1, 2, 3);   // 보낸 seq(99) 무시
    }

    @Test
    @DisplayName("작성 본문 검사: 다른 현장 siteId·DAILY 아닌 종류·formData 가 객체가 아니면 400. formData 를 비우면 기존 값 유지")
    void createValidationAndFormData() {
        Long otherSite = siteRepository.save(new Site("다른현장-" + UUID.randomUUID(), SiteMode.GUIDE)).getId();
        expectCode(() -> riskService.create(userId, new SaveRiskAssessmentRequest(otherSite, workId, AssessmentType.DAILY, DAY,
                null, List.of(item("x", null, 1, 1)), null)), ErrorCode.INVALID_REQUEST);
        expectCode(() -> riskService.create(userId, new SaveRiskAssessmentRequest(siteId, workId, AssessmentType.REGULAR, DAY,
                null, List.of(item("x", null, 1, 1)), null)), ErrorCode.INVALID_REQUEST);
        expectCode(() -> riskService.create(userId, new SaveRiskAssessmentRequest(siteId, workId, AssessmentType.DAILY, DAY,
                jsonMapper.valueToTree("[1,2]"), List.of(item("x", null, 1, 1)), null)), ErrorCode.INVALID_REQUEST);

        RiskAssessmentResponse r = riskService.create(userId, risk(null, item("x", null, 1, 1)));
        RiskAssessmentResponse kept = riskService.update(userId, r.riskAssessmentId(), new SaveRiskAssessmentRequest(null, null,
                null, DAY, null, List.of(item("y", null, 1, 1)), r.lockVersion()));
        assertThat(jsonMapper.readTree(kept.formData()).path("workSummary").asString()).isEqualTo("배관 교체");
    }

    @Test
    @DisplayName("현장 작업자 목록: 배치된 재직 작업자만 이름 순")
    void siteWorkers() {
        Long assigned = worker(true);
        worker(false);
        Long left = worker(true);
        workerRepository.findById(left).orElseThrow().deactivate();
        workerRepository.flush();

        List<SiteWorkerResponse> list = siteWorkerService.list(userId, siteId);
        assertThat(list).extracting(SiteWorkerResponse::workerId).containsExactly(assigned);
    }

    @Test
    @DisplayName("참석자만 바꾸기: lockVersion 확인, 저장하면 lockVersion 이 오른다, 배치 안 된 작업자는 400")
    void saveAttendeesOnly() {
        Long w1 = worker(true);
        Long w2 = worker(true);
        TbmResponse t = tbmService.create(userId, siteId, tbm(DAY, List.of(workId), List.of(w1), null));

        expectCode(() -> tbmService.saveAttendees(userId, t.tbmId(), new SaveTbmAttendeesRequest(t.lockVersion() + 1,
                List.of(new SaveTbmRequest.Attendee(w2, true)))), ErrorCode.CONFLICT_MODIFIED);
        TbmResponse saved = tbmService.saveAttendees(userId, t.tbmId(), new SaveTbmAttendeesRequest(t.lockVersion(),
                List.of(new SaveTbmRequest.Attendee(w2, false))));

        assertThat(saved.attendees()).extracting(TbmResponse.Attendee::workerId, TbmResponse.Attendee::healthOk)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(w2, false));
        assertThat(saved.lockVersion()).isGreaterThan(t.lockVersion());
        assertThat(saved.works()).extracting(TbmResponse.WorkRef::workId).containsExactly(workId);   // 작업은 그대로
        Long outsider = worker(false);
        expectCode(() -> tbmService.saveAttendees(userId, t.tbmId(), new SaveTbmAttendeesRequest(saved.lockVersion(),
                List.of(new SaveTbmRequest.Attendee(outsider, true)))), ErrorCode.INVALID_REQUEST);
        expectCode(() -> tbmService.complete(userId, t.tbmId(), new CompleteTbmRequest(t.lockVersion(), DAY.atTime(8, 0), 3)),
                ErrorCode.CONFLICT_MODIFIED);   // 예전 lockVersion
    }
}
