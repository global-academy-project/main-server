package com.example.backend.worker.service;

import com.example.backend.site.service.SiteGuard;
import com.example.backend.worker.dto.SiteWorkerResponse;
import com.example.backend.worker.entity.Team;
import com.example.backend.worker.entity.Worker;
import com.example.backend.worker.entity.WorkerSite;
import com.example.backend.worker.repository.TeamRepository;
import com.example.backend.worker.repository.WorkerRepository;
import com.example.backend.worker.repository.WorkerSiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 현장 작업자 목록: 현장 배치(worker_sites)가 켜져 있고 작업자가 재직(ACTIVE)인 사람만, 이름 순 */
@Service
public class SiteWorkerService {

    private final WorkerSiteRepository workerSiteRepository;
    private final WorkerRepository workerRepository;
    private final TeamRepository teamRepository;
    private final SiteGuard siteGuard;

    public SiteWorkerService(WorkerSiteRepository workerSiteRepository, WorkerRepository workerRepository,
                             TeamRepository teamRepository, SiteGuard siteGuard) {
        this.workerSiteRepository = workerSiteRepository;
        this.workerRepository = workerRepository;
        this.teamRepository = teamRepository;
        this.siteGuard = siteGuard;
    }

    @Transactional(readOnly = true)
    public List<SiteWorkerResponse> list(Long userId, Long siteId) {
        siteGuard.requireAccess(userId, siteId);
        List<WorkerSite> assignments = workerSiteRepository.findBySiteIdAndActiveTrue(siteId);
        Map<Long, Worker> workers = workerRepository.findAllById(assignments.stream().map(WorkerSite::getWorkerId).toList())
                .stream().filter(Worker::isActive).collect(Collectors.toMap(Worker::getId, Function.identity()));
        Map<Long, String> teams = teamRepository.findAllById(workers.values().stream().map(Worker::getTeamId)
                        .filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Team::getId, Team::getName));
        return assignments.stream().filter(a -> workers.containsKey(a.getWorkerId())).map(a -> {
            Worker w = workers.get(a.getWorkerId());
            return new SiteWorkerResponse(w.getId(), w.getName(), w.getJobType(), w.getTeamId(),
                    w.getTeamId() == null ? null : teams.get(w.getTeamId()), w.getEmploymentType(),
                    a.getAssignedFrom(), a.getAssignedTo());
        }).sorted(Comparator.comparing(SiteWorkerResponse::name)).toList();
    }
}
