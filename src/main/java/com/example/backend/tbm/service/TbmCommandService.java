package com.example.backend.tbm.service;

import com.example.backend.approval.entity.ApprovalStatus;
import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.repository.ApprovalRepository;
import com.example.backend.approval.service.ApprovalService;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.site.repository.WorkZoneRepository;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.tbm.dto.CompleteTbmRequest;
import com.example.backend.tbm.dto.SaveTbmAttendeesRequest;
import com.example.backend.tbm.dto.SaveTbmRequest;
import com.example.backend.tbm.dto.TbmResponse;
import com.example.backend.tbm.entity.TbmAttendee;
import com.example.backend.tbm.entity.TbmRecord;
import com.example.backend.tbm.entity.TbmStatus;
import com.example.backend.tbm.entity.TbmWork;
import com.example.backend.tbm.repository.TbmAttendeeRepository;
import com.example.backend.tbm.repository.TbmRecordRepository;
import com.example.backend.tbm.repository.TbmWorkRepository;
import com.example.backend.user.entity.User;
import com.example.backend.work.entity.Work;
import com.example.backend.work.repository.WorkRepository;
import com.example.backend.worker.repository.TeamRepository;
import com.example.backend.worker.repository.WorkerSiteRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

/**
 * TBM 작성·수정·실시 완료·되돌리기·확인 요청 (계획서 7-5장: TBM 은 실시 완료 후 1단계 확인).
 * <ul>
 *   <li>작성 중(DRAFT)·실시 완료(DONE)는 고칠 수 있다. 확인 요청 중(결재 대기)이거나 확인된(APPROVED) TBM 은 못 고친다</li>
 *   <li>교육 요점은 JSON 배열로 저장 (에이전트 초안과 같은 형식)</li>
 *   <li>확인 요청은 실시 완료 후. 확인되면 APPROVED, 반려되면 작성 중으로 돌아간다</li>
 * </ul>
 */
@Service
public class TbmCommandService {

    private final TbmRecordRepository tbmRepository;
    private final TbmWorkRepository tbmWorkRepository;
    private final TbmAttendeeRepository attendeeRepository;
    private final WorkRepository workRepository;
    private final WorkZoneRepository zoneRepository;
    private final TeamRepository teamRepository;
    private final WorkerSiteRepository workerSiteRepository;
    private final ApprovalRepository approvalRepository;
    private final ApprovalService approvalService;
    private final RecordHistoryRepository historyRepository;
    private final TbmService tbmService;
    private final SiteGuard siteGuard;
    private final JsonMapper jsonMapper;
    private final EntityManager entityManager;

    public TbmCommandService(TbmRecordRepository tbmRepository, TbmWorkRepository tbmWorkRepository,
                             TbmAttendeeRepository attendeeRepository, WorkRepository workRepository,
                             WorkZoneRepository zoneRepository, TeamRepository teamRepository,
                             WorkerSiteRepository workerSiteRepository, ApprovalRepository approvalRepository,
                             ApprovalService approvalService, RecordHistoryRepository historyRepository,
                             TbmService tbmService, SiteGuard siteGuard, JsonMapper jsonMapper,
                             EntityManager entityManager) {
        this.tbmRepository = tbmRepository;
        this.tbmWorkRepository = tbmWorkRepository;
        this.attendeeRepository = attendeeRepository;
        this.workRepository = workRepository;
        this.zoneRepository = zoneRepository;
        this.teamRepository = teamRepository;
        this.workerSiteRepository = workerSiteRepository;
        this.approvalRepository = approvalRepository;
        this.approvalService = approvalService;
        this.historyRepository = historyRepository;
        this.tbmService = tbmService;
        this.siteGuard = siteGuard;
        this.jsonMapper = jsonMapper;
        this.entityManager = entityManager;
    }

    @Transactional
    public TbmResponse create(Long userId, Long siteId, SaveTbmRequest r) {
        User user = siteGuard.requireAccess(userId, siteId);
        checkTeamAndZone(siteId, r);
        TbmRecord tbm = tbmRepository.saveAndFlush(new TbmRecord(siteId, r.tbmDate(), r.teamId(), r.zoneId(), user.getId()));
        guard(() -> tbm.updateContent(r.teamId(), r.zoneId(), r.tbmDate(), trim(r.leaderName()), points(r), trim(r.healthCheck())));
        tbmRepository.saveAndFlush(tbm);
        replaceLinks(tbm, r);
        history(tbm, HistoryAction.CREATE, userId);
        return tbmService.get(userId, tbm.getId());
    }

    @Transactional
    public TbmResponse update(Long userId, Long tbmId, SaveTbmRequest r) {
        TbmRecord tbm = loadEditable(userId, tbmId);
        requireVersion(tbm, r.lockVersion());
        checkTeamAndZone(tbm.getSiteId(), r);
        // 날짜가 바뀌면 작업 연결(tbm_works.tbm_date)도 바뀌므로 연결을 먼저 지우고 다시 넣는다
        clearLinks(tbm);
        guard(() -> tbm.updateContent(r.teamId(), r.zoneId(), r.tbmDate(), trim(r.leaderName()), points(r), trim(r.healthCheck())));
        tbmRepository.saveAndFlush(tbm);
        replaceLinks(tbm, r);
        history(tbm, HistoryAction.UPDATE, userId);
        return tbmService.get(userId, tbmId);
    }

    /** 참석자만 통째로 바꾸기 (웹 TBM 화면). 작업·내용은 그대로 */
    @Transactional
    public TbmResponse saveAttendees(Long userId, Long tbmId, SaveTbmAttendeesRequest r) {
        TbmRecord tbm = loadEditable(userId, tbmId);
        requireVersion(tbm, r.lockVersion());
        List<TbmAttendee> old = attendeeRepository.findByTbmId(tbmId);
        attendeeRepository.deleteAll(old);
        attendeeRepository.flush();
        saveAttendeeRows(tbm, r.attendees());
        bumpVersion(tbm);   // 참석자만 바뀌어도 lockVersion 을 올려 동시 수정을 막는다
        history(tbm, HistoryAction.UPDATE, userId);
        return tbmService.get(userId, tbmId);
    }

    /** 실시 완료: 실시 시각·인원 (인원을 비우면 등록된 참석자 수) */
    @Transactional
    public TbmResponse complete(Long userId, Long tbmId, CompleteTbmRequest r) {
        TbmRecord tbm = loadEditable(userId, tbmId);
        requireVersion(tbm, r.lockVersion());
        int count = r.attendeeCount() != null ? r.attendeeCount() : attendeeRepository.countByTbmId(tbmId);
        if (r.attendeeCount() == null && count == 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "참석자를 등록하거나 인원을 입력해 주세요.");
        }
        if (!r.heldAt().toLocalDate().equals(tbm.getTbmDate())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "실시 시각은 TBM 날짜(" + tbm.getTbmDate() + ")여야 합니다.");
        }
        guard(() -> tbm.complete(r.heldAt(), count));
        tbmRepository.saveAndFlush(tbm);
        status(tbm, userId, "DONE");
        return tbmService.get(userId, tbmId);
    }

    /** 실시 완료를 되돌려 다시 작성 (확인 요청 전만) */
    @Transactional
    public TbmResponse reopen(Long userId, Long tbmId) {
        TbmRecord tbm = loadEditable(userId, tbmId);
        if (tbm.getStatus() != TbmStatus.DONE) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "실시 완료된 TBM만 되돌릴 수 있습니다.");
        }
        guard(tbm::reopen);
        tbmRepository.saveAndFlush(tbm);
        status(tbm, userId, "DRAFT");
        return tbmService.get(userId, tbmId);
    }

    /** 확인 요청 (결재 1단계, 현장 정책이 있으면 그 결재선). 실시 완료 후만 */
    @Transactional
    public TbmResponse submit(Long userId, Long tbmId) {
        TbmRecord tbm = loadEditable(userId, tbmId);
        if (tbm.getStatus() != TbmStatus.DONE) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "실시 완료한 뒤 확인 요청할 수 있습니다 (현재 " + tbm.getStatus() + ").");
        }
        approvalService.request(ApprovalTarget.TBM, tbmId, tbm.getSiteId(), userId);
        return tbmService.get(userId, tbmId);
    }

    // ------------------------------------------------------------------ 내부

    /** 그 현장 TBM 이고, 확인되지 않았고, 확인 요청 중이 아니어야 고칠 수 있다 */
    private TbmRecord loadEditable(Long userId, Long tbmId) {
        TbmRecord tbm = tbmRepository.findById(tbmId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "TBM 기록을 찾을 수 없습니다."));
        siteGuard.requireAccess(userId, tbm.getSiteId());
        if (!tbm.isEditable()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "확인된 TBM 기록은 고칠 수 없습니다.");
        }
        if (approvalRepository.findByTargetTypeAndTargetIdAndStatus(ApprovalTarget.TBM, tbmId, ApprovalStatus.PENDING).isPresent()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "확인 요청 중인 TBM입니다. 회수한 뒤 고쳐 주세요.");
        }
        return tbm;
    }

    private void checkTeamAndZone(Long siteId, SaveTbmRequest r) {
        if (r.zoneId() != null && zoneRepository.findByIdAndSiteId(r.zoneId(), siteId).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이 현장의 구역이 아닙니다: " + r.zoneId());
        }
        if (r.teamId() != null && !teamRepository.existsById(r.teamId())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "없는 팀입니다: " + r.teamId());
        }
    }

    private void clearLinks(TbmRecord tbm) {
        List<TbmWork> works = tbmWorkRepository.findByTbmId(tbm.getId());
        List<TbmAttendee> attendees = attendeeRepository.findByTbmId(tbm.getId());
        tbmWorkRepository.deleteAll(works);
        attendeeRepository.deleteAll(attendees);
        tbmWorkRepository.flush();
        attendeeRepository.flush();
    }

    /** 작업·참석자 연결 (중복은 한 번만). 작업: 같은 현장·같은 날·다른 TBM 에 없음. 참석자: 그 현장 배치 작업자 */
    private void replaceLinks(TbmRecord tbm, SaveTbmRequest r) {
        clearLinks(tbm);
        for (Long workId : new LinkedHashSet<>(r.workIds() == null ? List.<Long>of() : r.workIds())) {
            Work work = workRepository.findById(workId)
                    .filter(w -> w.getSiteId().equals(tbm.getSiteId()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "이 현장의 작업이 아닙니다: " + workId));
            if (!work.getWorkDate().equals(tbm.getTbmDate())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "작업일(" + work.getWorkDate() + ")이 TBM 날짜(" + tbm.getTbmDate() + ")와 다릅니다: " + workId);
            }
            if (tbmWorkRepository.existsByWorkIdAndTbmDate(workId, tbm.getTbmDate())) {
                throw new BusinessException(ErrorCode.INVALID_STATE, "이 작업은 그날 다른 TBM에 이미 들어 있습니다: " + workId);
            }
            tbmWorkRepository.save(TbmWork.of(tbm, work));
        }
        saveAttendeeRows(tbm, r.attendees());
        tbmWorkRepository.flush();
    }

    /** 참석자 저장 (같은 작업자는 한 번, 그 현장에 배치된 작업자만) */
    private void saveAttendeeRows(TbmRecord tbm, List<SaveTbmRequest.Attendee> list) {
        Map<Long, Boolean> attendees = new LinkedHashMap<>();
        for (SaveTbmRequest.Attendee a : list == null ? List.<SaveTbmRequest.Attendee>of() : list) {
            if (!workerSiteRepository.existsByWorkerIdAndSiteIdAndActiveTrue(a.workerId(), tbm.getSiteId())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "이 현장에 배치된 작업자가 아닙니다: " + a.workerId());
            }
            attendees.put(a.workerId(), a.healthOk());
        }
        attendees.forEach((workerId, ok) -> attendeeRepository.save(TbmAttendee.of(tbm, workerId, ok)));
        attendeeRepository.flush();
    }

    /**
     * TBM 행은 그대로이고 참석자만 바뀐 경우 lockVersion 을 1 올린다.
     * 그 사이 다른 사람이 바꿨으면(버전이 다르면) 409. 올린 뒤 엔티티를 DB 값으로 다시 읽는다.
     */
    private void bumpVersion(TbmRecord tbm) {
        entityManager.flush();
        int updated = entityManager.createQuery("update TbmRecord t set t.lockVersion = t.lockVersion + 1"
                        + " where t.id = :id and t.lockVersion = :v")
                .setParameter("id", tbm.getId()).setParameter("v", tbm.getLockVersion()).executeUpdate();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CONFLICT_MODIFIED);
        }
        entityManager.refresh(tbm);
    }

    private static void requireVersion(TbmRecord tbm, Integer lockVersion) {
        if (lockVersion == null || lockVersion != tbm.getLockVersion()) {
            throw new BusinessException(ErrorCode.CONFLICT_MODIFIED);
        }
    }

    /** 교육 요점 → JSON 배열 문자열 (빈칸 제외, 없으면 null) */
    private String points(SaveTbmRequest r) {
        if (r.points() == null) {
            return null;
        }
        List<String> list = r.points().stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        return list.isEmpty() ? null : jsonMapper.writeValueAsString(list);
    }

    private void history(TbmRecord tbm, HistoryAction action, Long userId) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("tbmDate", tbm.getTbmDate().toString());
        after.put("works", tbmWorkRepository.findByTbmId(tbm.getId()).size());
        after.put("attendees", attendeeRepository.countByTbmId(tbm.getId()));
        historyRepository.save(RecordHistory.byUser(tbm.getSiteId(), HistoryTarget.TBM, tbm.getId(), action, userId,
                null, jsonMapper.writeValueAsString(after)));
    }

    private void status(TbmRecord tbm, Long userId, String status) {
        historyRepository.save(RecordHistory.byUser(tbm.getSiteId(), HistoryTarget.TBM, tbm.getId(),
                HistoryAction.STATUS_CHANGE, userId, null, jsonMapper.writeValueAsString(Map.of("status", status))));
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void guard(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_STATE, e.getMessage());
        }
    }
}
