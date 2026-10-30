package com.example.backend.tbm.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.tbm.dto.TbmResponse;
import com.example.backend.tbm.entity.TbmAttendee;
import com.example.backend.tbm.entity.TbmRecord;
import com.example.backend.tbm.entity.TbmWork;
import com.example.backend.tbm.repository.TbmAttendeeRepository;
import com.example.backend.tbm.repository.TbmRecordRepository;
import com.example.backend.tbm.repository.TbmWorkRepository;
import com.example.backend.work.entity.Work;
import com.example.backend.work.repository.WorkRepository;
import com.example.backend.worker.entity.Worker;
import com.example.backend.worker.repository.WorkerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** TBM 조회. 작성·실시 완료·확인(결재) API 는 다음 (지금 기록은 에이전트 초안 저장으로 만들어진다, 7-2장) */
@Service
public class TbmService {

    private final TbmRecordRepository tbmRepository;
    private final TbmWorkRepository tbmWorkRepository;
    private final TbmAttendeeRepository attendeeRepository;
    private final WorkRepository workRepository;
    private final WorkerRepository workerRepository;
    private final SiteGuard siteGuard;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public TbmService(TbmRecordRepository tbmRepository, TbmWorkRepository tbmWorkRepository,
                      TbmAttendeeRepository attendeeRepository, WorkRepository workRepository,
                      WorkerRepository workerRepository, SiteGuard siteGuard, JsonMapper jsonMapper, Clock clock) {
        this.tbmRepository = tbmRepository;
        this.tbmWorkRepository = tbmWorkRepository;
        this.attendeeRepository = attendeeRepository;
        this.workRepository = workRepository;
        this.workerRepository = workerRepository;
        this.siteGuard = siteGuard;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TbmResponse get(Long userId, Long tbmId) {
        TbmRecord tbm = tbmRepository.findById(tbmId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "TBM 기록을 찾을 수 없습니다."));
        siteGuard.requireAccess(userId, tbm.getSiteId());
        return response(tbm);
    }

    /** 작업에 연결된 가장 최근 TBM */
    @Transactional(readOnly = true)
    public TbmResponse getByWork(Long userId, Long workId) {
        Work work = workRepository.findById(workId).orElseThrow(() -> new BusinessException(ErrorCode.WORK_NOT_FOUND));
        siteGuard.requireAccess(userId, work.getSiteId());
        TbmWork link = tbmWorkRepository.findByWorkIdOrderByTbmDateDesc(workId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "이 작업의 TBM 기록이 없습니다."));
        return get(userId, link.getTbmId());
    }

    /** 현장의 그날 TBM 목록. date 를 비우면 오늘(Asia/Seoul) */
    @Transactional(readOnly = true)
    public List<TbmResponse> listByDate(Long userId, Long siteId, LocalDate date) {
        siteGuard.requireAccess(userId, siteId);
        LocalDate day = date != null ? date : LocalDate.now(clock);
        return tbmRepository.findBySiteIdAndTbmDateOrderByIdAsc(siteId, day).stream().map(this::response).toList();
    }

    private TbmResponse response(TbmRecord tbm) {
        List<Long> workIds = tbmWorkRepository.findByTbmId(tbm.getId()).stream().map(TbmWork::getWorkId).toList();
        Map<Long, Work> works = workRepository.findAllById(workIds).stream()
                .collect(Collectors.toMap(Work::getId, Function.identity()));
        List<TbmResponse.WorkRef> workRefs = workIds.stream().filter(works::containsKey)
                .map(id -> new TbmResponse.WorkRef(id, works.get(id).getTitle())).toList();

        List<TbmAttendee> attendees = attendeeRepository.findByTbmId(tbm.getId());
        Map<Long, String> names = workerRepository.findAllById(attendees.stream().map(TbmAttendee::getWorkerId).toList())
                .stream().collect(Collectors.toMap(Worker::getId, Worker::getName));
        List<TbmResponse.Attendee> people = attendees.stream()
                .map(a -> new TbmResponse.Attendee(a.getWorkerId(), names.get(a.getWorkerId()), a.getHealthOk())).toList();

        return new TbmResponse(tbm.getId(), tbm.getSiteId(), tbm.getTbmDate(), tbm.getHeldAt(), tbm.getLeaderName(),
                tbm.getAttendeeCount(), points(tbm.getEducationPoints()), tbm.getHealthCheck(), tbm.getStatus(),
                tbm.getWriterId(), tbm.getAgentRunId(), tbm.getLockVersion(), workRefs, people);
    }

    /** 교육 요점: JSON 배열 문자열 → 목록. 배열이 아니면(직접 쓴 글) 원문 한 줄 */
    List<String> points(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = jsonMapper.readTree(raw);
            if (node.isArray()) {
                List<String> out = new ArrayList<>();
                node.forEach(n -> {
                    if (n.isString() && !n.asString().isBlank()) {
                        out.add(n.asString());
                    }
                });
                return out;
            }
        } catch (JacksonException e) {
            // 직접 쓴 글
        }
        return List.of(raw);
    }
}
