package com.example.backend.worker.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.worker.dto.SiteWorkerRequest;
import com.example.backend.worker.dto.SiteWorkerResponse;
import com.example.backend.worker.entity.Team;
import com.example.backend.worker.entity.Worker;
import com.example.backend.worker.entity.WorkerSite;
import com.example.backend.worker.entity.WorkerSiteId;
import com.example.backend.worker.repository.TeamRepository;
import com.example.backend.worker.repository.WorkerRepository;
import com.example.backend.worker.repository.WorkerSiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 현장 작업자: 목록(현장 권한), 등록·수정·배치·떠남(현장 관리자 이상).
 * 작업자는 회사 공통이고, 현장 배치(worker_sites)로 그 현장 사람이 된다. 연락처는 응답에 내보내지 않는다.
 */
@Service
public class SiteWorkerService {

    private final WorkerSiteRepository workerSiteRepository;
    private final WorkerRepository workerRepository;
    private final TeamRepository teamRepository;
    private final RecordHistoryRepository historyRepository;
    private final SiteGuard siteGuard;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public SiteWorkerService(WorkerSiteRepository workerSiteRepository, WorkerRepository workerRepository,
                             TeamRepository teamRepository, RecordHistoryRepository historyRepository,
                             SiteGuard siteGuard, JsonMapper jsonMapper, Clock clock) {
        this.workerSiteRepository = workerSiteRepository;
        this.workerRepository = workerRepository;
        this.teamRepository = teamRepository;
        this.historyRepository = historyRepository;
        this.siteGuard = siteGuard;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** 현장 배치 중이고 재직(ACTIVE)인 작업자, 이름 순 */
    @Transactional(readOnly = true)
    public List<SiteWorkerResponse> list(Long userId, Long siteId) {
        siteGuard.requireAccess(userId, siteId);
        List<WorkerSite> assignments = workerSiteRepository.findBySiteIdAndActiveTrue(siteId);
        Map<Long, Worker> workers = workerRepository.findAllById(assignments.stream().map(WorkerSite::getWorkerId).toList())
                .stream().filter(Worker::isActive).collect(Collectors.toMap(Worker::getId, Function.identity()));
        Map<Long, String> teams = teamNames(workers.values());
        return assignments.stream().filter(a -> workers.containsKey(a.getWorkerId()))
                .map(a -> response(workers.get(a.getWorkerId()), a, teams))
                .sorted(Comparator.comparing(SiteWorkerResponse::name)).toList();
    }

    /** 새 작업자 등록 + 이 현장 배치 */
    @Transactional
    public SiteWorkerResponse create(Long userId, Long siteId, SiteWorkerRequest r) {
        siteGuard.requireSiteAdmin(userId, siteId);
        checkTeam(r.teamId());
        Worker worker = guard(() -> new Worker(r.teamId(), r.name(), r.phone(), blank(r.jobType()), r.employmentType()));
        workerRepository.saveAndFlush(worker);
        WorkerSite assignment = workerSiteRepository.saveAndFlush(
                new WorkerSite(worker.getId(), siteId, r.assignedFrom() != null ? r.assignedFrom() : LocalDate.now(clock)));
        history(siteId, worker, HistoryAction.CREATE, userId);
        return response(worker, assignment, teamNames(List.of(worker)));
    }

    /** 이름·연락처·직종·고용 형태·팀 수정 (이 현장에 배치된 작업자만, lockVersion 필수) */
    @Transactional
    public SiteWorkerResponse update(Long userId, Long siteId, Long workerId, SiteWorkerRequest r) {
        siteGuard.requireSiteAdmin(userId, siteId);
        WorkerSite assignment = assignment(workerId, siteId)
                .filter(WorkerSite::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "이 현장에 배치된 작업자가 아닙니다."));
        Worker worker = workerRepository.findById(workerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "작업자를 찾을 수 없습니다."));
        if (r.lockVersion() == null || r.lockVersion() != worker.getLockVersion()) {
            throw new BusinessException(ErrorCode.CONFLICT_MODIFIED);
        }
        checkTeam(r.teamId());
        // 연락처는 응답에 없어 화면이 다시 보낼 수 없다: 안 보내면(null) 그대로, 빈 문자열이면 지움
        String phone = r.phone() == null ? worker.getPhone() : r.phone();
        guard(() -> {
            worker.update(r.name(), phone, blank(r.jobType()), r.employmentType());
            worker.changeTeam(r.teamId());
            return worker;
        });
        workerRepository.saveAndFlush(worker);
        history(siteId, worker, HistoryAction.UPDATE, userId);
        return response(worker, assignment, teamNames(List.of(worker)));
    }

    /** 다른 현장에서 일하던(이미 등록된) 작업자를 이 현장에 배치. 예전에 떠났으면 다시 배치 */
    @Transactional
    public SiteWorkerResponse assign(Long userId, Long siteId, Long workerId, LocalDate date) {
        siteGuard.requireSiteAdmin(userId, siteId);
        Worker worker = workerRepository.findById(workerId).filter(Worker::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "재직 중인 작업자를 찾을 수 없습니다."));
        LocalDate day = date != null ? date : LocalDate.now(clock);
        Optional<WorkerSite> existing = assignment(workerId, siteId);
        WorkerSite a;
        if (existing.isPresent()) {
            a = existing.get();
            if (a.isActive()) {
                throw new BusinessException(ErrorCode.INVALID_STATE, "이미 이 현장에 배치된 작업자입니다.");
            }
            WorkerSite rejoined = a;
            guard(() -> {
                rejoined.rejoin(day);
                return rejoined;
            });
        } else {
            a = new WorkerSite(workerId, siteId, day);
        }
        workerSiteRepository.saveAndFlush(a);
        historyRepository.save(RecordHistory.byUser(siteId, HistoryTarget.WORKER, workerId, HistoryAction.GRANT, userId, null,
                jsonMapper.writeValueAsString(Map.of("siteId", siteId, "assignedFrom", day.toString()))));
        return response(worker, a, teamNames(List.of(worker)));
    }

    /** 이 현장을 떠남 (배치 행은 지우지 않고 떠난 날을 남긴다) */
    @Transactional
    public void leave(Long userId, Long siteId, Long workerId, LocalDate date) {
        siteGuard.requireSiteAdmin(userId, siteId);
        WorkerSite a = assignment(workerId, siteId).filter(WorkerSite::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "이 현장에 배치된 작업자가 아닙니다."));
        LocalDate day = date != null ? date : LocalDate.now(clock);
        guard(() -> {
            a.leave(day);
            return a;
        });
        workerSiteRepository.saveAndFlush(a);
        historyRepository.save(RecordHistory.byUser(siteId, HistoryTarget.WORKER, workerId, HistoryAction.REVOKE, userId, null,
                jsonMapper.writeValueAsString(Map.of("siteId", siteId, "assignedTo", day.toString()))));
    }

    // ------------------------------------------------------------------ 내부

    private Optional<WorkerSite> assignment(Long workerId, Long siteId) {
        return workerSiteRepository.findById(new WorkerSiteId(workerId, siteId));
    }

    private void checkTeam(Long teamId) {
        if (teamId != null && teamRepository.findById(teamId).filter(Team::isActive).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "사용 중인 팀이 아닙니다: " + teamId);
        }
    }

    private Map<Long, String> teamNames(Collection<Worker> workers) {
        Set<Long> ids = workers.stream().map(Worker::getTeamId).filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? new HashMap<>() : teamRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Team::getId, Team::getName));
    }

    private static SiteWorkerResponse response(Worker w, WorkerSite a, Map<Long, String> teams) {
        return new SiteWorkerResponse(w.getId(), w.getName(), w.getJobType(), w.getTeamId(),
                w.getTeamId() == null ? null : teams.get(w.getTeamId()), w.getEmploymentType(),
                a.getAssignedFrom(), a.getAssignedTo(), w.getLockVersion());
    }

    /** 이력에는 연락처를 남기지 않는다 */
    private void history(Long siteId, Worker w, HistoryAction action, Long userId) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", w.getName());
        after.put("jobType", w.getJobType());
        after.put("teamId", w.getTeamId());
        historyRepository.save(RecordHistory.byUser(siteId, HistoryTarget.WORKER, w.getId(), action, userId, null,
                jsonMapper.writeValueAsString(after)));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static <T> T guard(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, e.getMessage());
        }
    }
}
