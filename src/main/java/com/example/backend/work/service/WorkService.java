package com.example.backend.work.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.user.entity.User;
import com.example.backend.work.dto.CreateWorkRequest;
import com.example.backend.work.dto.WorkResponse;
import com.example.backend.work.entity.Work;
import com.example.backend.work.repository.WorkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 작업 최소 기능: 등록·조회 (허가서가 붙을 작업). 작업 등록 대화·승인 흐름은 작업 등록 기능에서 넓힌다 */
@Service
public class WorkService {

    private final WorkRepository workRepository;
    private final SiteGuard siteGuard;

    public WorkService(WorkRepository workRepository, SiteGuard siteGuard) {
        this.workRepository = workRepository;
        this.siteGuard = siteGuard;
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
}
