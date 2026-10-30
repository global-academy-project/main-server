package com.example.backend.work.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.permit.entity.Permit;
import com.example.backend.permit.repository.PermitRepository;
import com.example.backend.risk.entity.RiskAssessment;
import com.example.backend.risk.repository.RiskAssessmentRepository;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.tbm.entity.TbmRecord;
import com.example.backend.tbm.entity.TbmWork;
import com.example.backend.tbm.repository.TbmRecordRepository;
import com.example.backend.tbm.repository.TbmWorkRepository;
import com.example.backend.user.entity.User;
import com.example.backend.work.dto.CreateWorkRequest;
import com.example.backend.work.dto.WorkListItem;
import com.example.backend.work.dto.WorkResponse;
import com.example.backend.work.entity.Work;
import com.example.backend.work.repository.WorkRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 작업: 등록·조회·현장 기간 목록 (허가서·위험성평가·TBM 상태 포함) */
@Service
public class WorkService {

    private final WorkRepository workRepository;
    private final PermitRepository permitRepository;
    private final RiskAssessmentRepository riskRepository;
    private final TbmWorkRepository tbmWorkRepository;
    private final TbmRecordRepository tbmRepository;
    private final SiteGuard siteGuard;
    private final Clock clock;
    private final int listMaxDays;
    private final int listMaxRows;

    public WorkService(WorkRepository workRepository, PermitRepository permitRepository,
                       RiskAssessmentRepository riskRepository, TbmWorkRepository tbmWorkRepository,
                       TbmRecordRepository tbmRepository, SiteGuard siteGuard, Clock clock,
                       @Value("${app.work.list.max-days}") int listMaxDays,
                       @Value("${app.work.list.max-rows}") int listMaxRows) {
        this.workRepository = workRepository;
        this.permitRepository = permitRepository;
        this.riskRepository = riskRepository;
        this.tbmWorkRepository = tbmWorkRepository;
        this.tbmRepository = tbmRepository;
        this.siteGuard = siteGuard;
        this.clock = clock;
        this.listMaxDays = listMaxDays;
        this.listMaxRows = listMaxRows;
    }

    @Transactional
    public WorkResponse create(Long userId, CreateWorkRequest r) {
        User user = siteGuard.requireAccess(userId, r.siteId());
        if (r.startAt() != null && r.endAt() != null && !r.endAt().isAfter(r.startAt())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "종료 시각은 시작 시각보다 뒤여야 합니다.");
        }
        Work work = workRepository.save(new Work(r.siteId(), r.workDate(), r.title().trim(), r.description(),
                r.workType(), r.startAt(), r.endAt(), r.workerCount(), user.getId()));
        return WorkResponse.from(work);
    }

    @Transactional(readOnly = true)
    public WorkResponse get(Long userId, Long workId) {
        Work work = workRepository.findById(workId).orElseThrow(() -> new BusinessException(ErrorCode.WORK_NOT_FOUND));
        siteGuard.requireAccess(userId, work.getSiteId());
        return WorkResponse.from(work);
    }

    /**
     * 현장의 기간 작업 목록. from·to 를 비우면 오늘(Asia/Seoul) 하루.
     * 기간은 yaml app.work.list.max-days 일까지, 행은 max-rows 까지 (날짜·시작 시각 순)
     */
    @Transactional(readOnly = true)
    public List<WorkListItem> list(Long userId, Long siteId, LocalDate from, LocalDate to) {
        siteGuard.requireAccess(userId, siteId);
        LocalDate today = LocalDate.now(clock);
        LocalDate start = from != null ? from : (to != null ? to : today);
        LocalDate end = to != null ? to : start;
        if (end.isBefore(start)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "to 는 from 과 같거나 뒤여야 합니다.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > listMaxDays) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "작업 목록은 한 번에 " + listMaxDays + "일까지 볼 수 있습니다.");
        }
        List<Work> works = workRepository.findBySiteIdAndWorkDateBetweenOrderByWorkDateAscStartAtAscIdAsc(siteId, start, end,
                PageRequest.of(0, listMaxRows));
        if (works.isEmpty()) {
            return List.of();
        }
        List<Long> ids = works.stream().map(Work::getId).toList();

        // 허가서: 작업·종류별 최신 판
        Map<Long, List<WorkListItem.PermitBrief>> permits = permitRepository.findByWorkIdIn(ids).stream()
                .collect(Collectors.groupingBy(p -> p.getWorkId() + ":" + p.getPermitType(),
                        Collectors.maxBy(Comparator.comparingInt(Permit::getRevision))))
                .values().stream().flatMap(java.util.Optional::stream)
                .sorted(Comparator.comparing(Permit::getPermitType).thenComparing(Permit::getId))
                .collect(Collectors.groupingBy(Permit::getWorkId, Collectors.mapping(p -> new WorkListItem.PermitBrief(
                        p.getId(), p.getPermitType(), p.getPermitNo(), p.getRevision(), p.getStatus()), Collectors.toList())));

        // 위험성평가: 작업별 최신 판
        Map<Long, RiskAssessment> risks = riskRepository.findByWorkIdIn(ids).stream()
                .collect(Collectors.toMap(RiskAssessment::getWorkId, Function.identity(),
                        (a, b) -> a.getRevision() >= b.getRevision() ? a : b));

        // TBM: 작업별 가장 최근 날짜
        Map<Long, TbmWork> tbmLinks = tbmWorkRepository.findByWorkIdIn(ids).stream()
                .collect(Collectors.toMap(TbmWork::getWorkId, Function.identity(),
                        (a, b) -> !a.getTbmDate().isBefore(b.getTbmDate()) ? a : b));
        Map<Long, TbmRecord> tbms = tbmRepository.findAllById(tbmLinks.values().stream().map(TbmWork::getTbmId)
                        .collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(TbmRecord::getId, Function.identity()));

        return works.stream().map(w -> {
            RiskAssessment r = risks.get(w.getId());
            TbmWork link = tbmLinks.get(w.getId());
            TbmRecord t = link == null ? null : tbms.get(link.getTbmId());
            return WorkListItem.of(w, permits.getOrDefault(w.getId(), List.of()),
                    r == null ? null : new WorkListItem.RiskBrief(r.getId(), r.getRevision(), r.getStatus()),
                    t == null ? null : new WorkListItem.TbmBrief(t.getId(), t.getTbmDate(), t.getStatus()));
        }).toList();
    }
}
