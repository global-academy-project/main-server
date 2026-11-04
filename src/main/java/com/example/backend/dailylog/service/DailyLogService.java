package com.example.backend.dailylog.service;

import com.example.backend.approval.entity.Approval;
import com.example.backend.approval.entity.ApprovalTarget;
import com.example.backend.approval.repository.ApprovalRepository;
import com.example.backend.approval.service.ApprovalService;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.dailylog.dto.DailyLogListItem;
import com.example.backend.dailylog.dto.DailyLogResponse;
import com.example.backend.dailylog.dto.UpdateDailyLogRequest;
import com.example.backend.dailylog.entity.DailyLogStatus;
import com.example.backend.dailylog.entity.DailySafetyLog;
import com.example.backend.dailylog.repository.DailySafetyLogRepository;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 일일 안전일지 (계획서 7장·7-5장, D 화면 요청서). 현장 × 날짜 1건.
 *  - 초안: 그날 기록을 서버가 집계(DailyLogAggregator)해 요약·집계·미완료·근거를 만든다. 작성 중이면 다시 집계해 덮어쓴다
 *  - 수정: 요약만 (작성 중일 때). 집계는 사람이 고치지 않는다
 *  - 승인 요청: 요약 필수 → PENDING + 결재. 승인·반려·회수는 결재 API (DailyLogApprovalHandler)
 * stats 컬럼에는 {"stats": {...}, "checks": [...], "evidence": [...]} 를 함께 보관한다 (초안 시점 그대로).
 */
@Service
public class DailyLogService {

    private final DailySafetyLogRepository logRepository;
    private final DailyLogAggregator aggregator;
    private final ApprovalService approvalService;
    private final ApprovalRepository approvalRepository;
    private final RecordHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final SiteGuard siteGuard;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final int listMaxDays;

    public DailyLogService(DailySafetyLogRepository logRepository, DailyLogAggregator aggregator,
                           ApprovalService approvalService, ApprovalRepository approvalRepository,
                           RecordHistoryRepository historyRepository, UserRepository userRepository, SiteGuard siteGuard,
                           JsonMapper jsonMapper, Clock clock, @Value("${app.daily-log.list.max-days}") int listMaxDays) {
        this.logRepository = logRepository;
        this.aggregator = aggregator;
        this.approvalService = approvalService;
        this.approvalRepository = approvalRepository;
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
        this.siteGuard = siteGuard;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.listMaxDays = listMaxDays;
    }

    /** 기간 목록 (날짜 내림차순). 비우면 오늘 하루 */
    @Transactional(readOnly = true)
    public List<DailyLogListItem> list(Long userId, Long siteId, LocalDate from, LocalDate to) {
        siteGuard.requireAccess(userId, siteId);
        LocalDate today = LocalDate.now(clock);
        LocalDate start = from != null ? from : (to != null ? to : today);
        LocalDate end = to != null ? to : start;
        if (end.isBefore(start)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "to 는 from 과 같거나 뒤여야 합니다.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > listMaxDays) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "안전일지 목록은 한 번에 " + listMaxDays + "일까지 볼 수 있습니다.");
        }
        return logRepository.findBySiteIdAndLogDateBetweenOrderByLogDateDesc(siteId, start, end).stream()
                .map(this::listItem).toList();
    }

    /** 초안 생성. 없으면 만들고, 작성 중이면 다시 집계해 덮어쓴다 (고친 요약도 새 집계 문장으로 바뀜) */
    @Transactional
    public DailyLogResponse draft(Long userId, Long siteId, LocalDate date) {
        if (date.isAfter(LocalDate.now(clock))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "오늘 이후 날짜의 안전일지는 만들 수 없습니다.");
        }
        DailyLogAggregator.Snapshot snap = aggregator.build(userId, siteId, date);   // 현장 권한 확인 포함
        String stats = jsonMapper.writeValueAsString(Map.of(
                "stats", snap.stats(), "checks", snap.checks(), "evidence", snap.evidence()));
        DailySafetyLog log = logRepository.findBySiteIdAndLogDate(siteId, date).orElse(null);
        boolean created = log == null;
        if (created) {
            log = DailySafetyLog.byUser(siteId, date, userId);
        } else if (log.getStatus() != DailyLogStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_STATE, log.getStatus() == DailyLogStatus.PENDING
                    ? "승인 요청 중인 안전일지입니다. 회수한 뒤 다시 만들어 주세요."
                    : "승인된 안전일지는 다시 만들 수 없습니다.");
        }
        DailySafetyLog target = log;
        guard(() -> target.update(snap.summary(), stats, userId));
        logRepository.saveAndFlush(target);
        history(target, created ? HistoryAction.CREATE : HistoryAction.UPDATE, userId, "draft");
        return response(target);
    }

    @Transactional(readOnly = true)
    public DailyLogResponse get(Long userId, Long dailyLogId) {
        return response(load(userId, dailyLogId));
    }

    /** 요약 수정 (작성 중일 때만) */
    @Transactional
    public DailyLogResponse update(Long userId, Long dailyLogId, UpdateDailyLogRequest r) {
        DailySafetyLog log = loadDraft(userId, dailyLogId, r.lockVersion());
        guard(() -> log.update(r.summary(), log.getStats(), userId));
        logRepository.saveAndFlush(log);
        history(log, HistoryAction.UPDATE, userId, "summary");
        return response(log);
    }

    /** 승인 요청. 요약이 비어 있으면 400 */
    @Transactional
    public DailyLogResponse submit(Long userId, Long dailyLogId, int lockVersion) {
        DailySafetyLog log = loadDraft(userId, dailyLogId, lockVersion);
        if (log.getSummary() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "요약을 작성한 뒤 승인 요청하세요.");
        }
        guard(log::submit);
        logRepository.saveAndFlush(log);
        approvalService.request(ApprovalTarget.DAILY_LOG, log.getId(), log.getSiteId(), userId);
        history(log, HistoryAction.SUBMIT, userId, null);
        return response(log);
    }

    // ------------------------------------------------------------------ 내부

    private DailySafetyLog load(Long userId, Long dailyLogId) {
        DailySafetyLog log = logRepository.findById(dailyLogId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "안전일지를 찾을 수 없습니다."));
        siteGuard.requireAccess(userId, log.getSiteId());
        return log;
    }

    private DailySafetyLog loadDraft(Long userId, Long dailyLogId, int lockVersion) {
        DailySafetyLog log = load(userId, dailyLogId);
        if (log.getStatus() != DailyLogStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_STATE, log.getStatus() == DailyLogStatus.PENDING
                    ? "승인 요청 중인 안전일지입니다. 회수한 뒤 고쳐 주세요."
                    : "승인된 안전일지는 고칠 수 없습니다.");
        }
        if (lockVersion != log.getLockVersion()) {
            throw new BusinessException(ErrorCode.CONFLICT_MODIFIED);
        }
        return log;
    }

    private DailyLogResponse response(DailySafetyLog l) {
        JsonNode stored = l.getStats() == null ? null : jsonMapper.readTree(l.getStats());
        Approval approval = approval(l.getId());
        return new DailyLogResponse(l.getId(), l.getSiteId(), l.getLogDate(), l.getStatus(), l.getSummary(),
                stored == null ? null : stored.get("stats"), stored == null ? null : stored.get("checks"),
                stored == null ? null : stored.get("evidence"), l.getWriterId(), writerName(l.getWriterId()),
                l.getAgentRunId(), approval == null ? null : approval.getId(),
                approval == null ? null : approval.getStatus(),
                l.getStatus() == DailyLogStatus.DRAFT && l.getSummary() != null,
                l.getCreatedAt(), l.getUpdatedAt(), l.getLockVersion());
    }

    private DailyLogListItem listItem(DailySafetyLog l) {
        Approval approval = approval(l.getId());
        return new DailyLogListItem(l.getId(), l.getSiteId(), l.getLogDate(), l.getStatus(), l.getSummary(),
                l.getWriterId(), writerName(l.getWriterId()), l.getAgentRunId(),
                approval == null ? null : approval.getId(), approval == null ? null : approval.getStatus(),
                l.getCreatedAt(), l.getUpdatedAt(), l.getLockVersion());
    }

    private Approval approval(Long dailyLogId) {
        return approvalRepository.findFirstByTargetTypeAndTargetIdOrderByRequestedAtDescIdDesc(ApprovalTarget.DAILY_LOG, dailyLogId)
                .orElse(null);
    }

    private String writerName(Long writerId) {
        return writerId == null ? null : userRepository.findById(writerId).map(User::getName).orElse(null);
    }

    private void history(DailySafetyLog l, HistoryAction action, Long userId, String what) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("date", l.getLogDate().toString());
        after.put("status", l.getStatus().name());
        if (what != null) {
            after.put("change", what);
        }
        historyRepository.save(RecordHistory.byUser(l.getSiteId(), HistoryTarget.DAILY_LOG, l.getId(), action, userId, null,
                jsonMapper.writeValueAsString(after)));
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
