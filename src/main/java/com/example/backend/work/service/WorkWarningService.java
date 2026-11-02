package com.example.backend.work.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.nearmiss.entity.NearMiss;
import com.example.backend.nearmiss.repository.NearMissRepository;
import com.example.backend.notification.entity.Notification;
import com.example.backend.notification.entity.NotificationRecipient;
import com.example.backend.notification.entity.NotificationSeverity;
import com.example.backend.notification.entity.NotificationTarget;
import com.example.backend.notification.entity.NotificationType;
import com.example.backend.notification.repository.NotificationRecipientRepository;
import com.example.backend.notification.repository.NotificationRepository;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.WorkZone;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.site.repository.WorkZoneRepository;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.tag.entity.RecordType;
import com.example.backend.tag.entity.TagAxis;
import com.example.backend.tag.repository.RecordTagRepository;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.work.dto.WorkWarningResponse;
import com.example.backend.work.entity.WarningSeverity;
import com.example.backend.work.entity.Work;
import com.example.backend.work.entity.WorkStatus;
import com.example.backend.work.entity.WorkWarning;
import com.example.backend.work.repository.WorkRepository;
import com.example.backend.work.repository.WorkWarningRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 같은 구역 아차사고 경고 (시나리오 C, 계획서 5장 "같은 구역·작업 신청 시 경고").
 * <ul>
 *   <li>아차사고 보고 → 같은 구역의 오늘 이후 작업(완료·취소 제외)에 경고, 작성자에게 알림</li>
 *   <li>구역을 정해 작업 등록 → 그 구역의 최근 아차사고(현장 설정 near_miss_warn_days 일)로 경고</li>
 *   <li>중대도 태그가 중상·사망(SERIOUS·FATAL)이면 긴급(CRITICAL, 확인에 조치 내용 필수), 아니면 WARN</li>
 *   <li>같은 아차사고로 같은 작업에 한 번만 (DB uk_ww_nm). 경고 내용은 바꾸지 않고 확인만 한 번 남긴다</li>
 *   <li>확인하지 않은 경고가 있으면 작업허가 승인 요청을 막는다 (PermitService)</li>
 * </ul>
 */
@Service
public class WorkWarningService {

    private static final Set<String> CRITICAL_SEVERITY = Set.of("SERIOUS", "FATAL");
    private static final Set<WorkStatus> CLOSED = Set.of(WorkStatus.DONE, WorkStatus.CANCELED);

    private final WorkWarningRepository warningRepository;
    private final WorkRepository workRepository;
    private final NearMissRepository nearMissRepository;
    private final RecordTagRepository tagRepository;
    private final SiteRepository siteRepository;
    private final WorkZoneRepository zoneRepository;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationRecipientRepository recipientRepository;
    private final RecordHistoryRepository historyRepository;
    private final SiteGuard siteGuard;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public WorkWarningService(WorkWarningRepository warningRepository, WorkRepository workRepository,
                              NearMissRepository nearMissRepository, RecordTagRepository tagRepository,
                              SiteRepository siteRepository, WorkZoneRepository zoneRepository,
                              UserRepository userRepository, NotificationRepository notificationRepository,
                              NotificationRecipientRepository recipientRepository,
                              RecordHistoryRepository historyRepository, SiteGuard siteGuard, JsonMapper jsonMapper,
                              Clock clock) {
        this.warningRepository = warningRepository;
        this.workRepository = workRepository;
        this.nearMissRepository = nearMissRepository;
        this.tagRepository = tagRepository;
        this.siteRepository = siteRepository;
        this.zoneRepository = zoneRepository;
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
        this.recipientRepository = recipientRepository;
        this.historyRepository = historyRepository;
        this.siteGuard = siteGuard;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** 아차사고 보고 직후: 같은 구역의 오늘 이후 작업에 경고 + 작업 작성자 알림. 경고한 작업 수 */
    @Transactional
    public int warnFromNearMiss(NearMiss n) {
        if (n.getZoneId() == null) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        WarningSeverity severity = severityOf(n);
        String message = message(n);
        int count = 0;
        for (Work w : workRepository.findBySiteIdAndZoneIdAndWorkDateGreaterThanEqualOrderByWorkDateAscIdAsc(
                n.getSiteId(), n.getZoneId(), now.toLocalDate())) {
            if (CLOSED.contains(w.getStatus()) || Objects.equals(w.getId(), n.getWorkId())
                    || warningRepository.existsByWorkIdAndNearMissId(w.getId(), n.getId())) {
                continue;
            }
            warningRepository.save(WorkWarning.fromNearMiss(w.getSiteId(), w.getId(), n.getId(), severity, message));
            notifyWriter(w, n, severity, message, now);
            count++;
        }
        warningRepository.flush();
        return count;
    }

    /** 구역을 정해 작업 등록 직후: 그 구역 최근 아차사고(현장 설정 기간)로 경고. 만든 경고 수 */
    @Transactional
    public int warnForWork(Work w) {
        if (w.getZoneId() == null) {
            return 0;
        }
        Site site = siteRepository.findById(w.getSiteId()).orElseThrow(() -> new BusinessException(ErrorCode.SITE_NOT_FOUND));
        LocalDateTime from = LocalDate.now(clock).minusDays(site.getNearMissWarnDays()).atStartOfDay();
        int count = 0;
        for (NearMiss n : nearMissRepository.findBySiteIdAndZoneIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                w.getSiteId(), w.getZoneId(), from)) {
            if (warningRepository.existsByWorkIdAndNearMissId(w.getId(), n.getId())) {
                continue;
            }
            warningRepository.save(WorkWarning.fromNearMiss(w.getSiteId(), w.getId(), n.getId(), severityOf(n), message(n)));
            count++;
        }
        warningRepository.flush();
        return count;
    }

    @Transactional(readOnly = true)
    public List<WorkWarningResponse> list(Long userId, Long workId) {
        Work w = workRepository.findById(workId).orElseThrow(() -> new BusinessException(ErrorCode.WORK_NOT_FOUND));
        siteGuard.requireAccess(userId, w.getSiteId());
        List<WorkWarning> rows = warningRepository.findByWorkIdOrderByShownAtAscIdAsc(workId);
        Map<Long, String> names = userRepository.findAllById(rows.stream().map(WorkWarning::getAcknowledgedBy)
                        .filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(User::getId, User::getName));
        return rows.stream().map(x -> response(x, names.get(x.getAcknowledgedBy()))).toList();
    }

    @Transactional
    public WorkWarningResponse acknowledge(Long userId, Long warningId, String note) {
        WorkWarning x = warningRepository.findById(warningId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "경고를 찾을 수 없습니다."));
        User user = siteGuard.requireAccess(userId, x.getSiteId());
        try {
            x.acknowledge(user.getId(), LocalDateTime.now(clock), note);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_STATE, e.getMessage());
        }
        warningRepository.saveAndFlush(x);
        historyRepository.save(RecordHistory.byUser(x.getSiteId(), HistoryTarget.WARNING, x.getId(), HistoryAction.ACKNOWLEDGE,
                user.getId(), null, jsonMapper.writeValueAsString(Map.of("workId", x.getWorkId(),
                        "severity", x.getSeverity(), "note", x.getAckNote() == null ? "" : x.getAckNote()))));
        return response(x, user.getName());
    }

    /** 확인하지 않은 경고 수 (작업허가 승인 요청 전 확인) */
    @Transactional(readOnly = true)
    public int unacknowledged(Long workId) {
        return workId == null ? 0 : warningRepository.findByWorkIdAndAcknowledgedAtIsNullOrderByIdAsc(workId).size();
    }

    // ------------------------------------------------------------------ 내부

    private WarningSeverity severityOf(NearMiss n) {
        boolean critical = tagRepository.findByRecordTypeAndRecordIdOrderByAxisAscIdAsc(RecordType.NEAR_MISS, n.getId()).stream()
                .anyMatch(t -> t.getAxis() == TagAxis.SEVERITY && CRITICAL_SEVERITY.contains(t.getValue()));
        return critical ? WarningSeverity.CRITICAL : WarningSeverity.WARN;
    }

    private String message(NearMiss n) {
        String zone = n.getZoneId() == null ? "같은 구역"
                : zoneRepository.findById(n.getZoneId()).map(WorkZone::getName).orElse("같은 구역");
        String text = "같은 구역(" + zone + ")에서 " + n.getOccurredAt().toLocalDate() + " 아차사고: " + n.getTitle();
        if (n.getImmediateAction() != null) {
            text += " / 당시 조치: " + n.getImmediateAction();
        }
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private void notifyWriter(Work w, NearMiss n, WarningSeverity severity, String message, LocalDateTime now) {
        if (w.getWriterId() == null || w.getWriterId().equals(n.getReporterId())) {
            return;
        }
        String key = "NEAR_MISS_WARNING:" + n.getId() + ":WORK:" + w.getId();
        if (notificationRepository.findByDedupeKey(key).isPresent()) {
            return;
        }
        String title = "같은 구역 아차사고 · " + w.getTitle();
        Notification saved = notificationRepository.save(Notification.of(w.getSiteId(), NotificationType.NEAR_MISS_WARNING,
                        severity == WarningSeverity.CRITICAL ? NotificationSeverity.CRITICAL : NotificationSeverity.WARN,
                        title.length() <= 100 ? title : title.substring(0, 100), message, now)
                .about(NotificationTarget.WORK, w.getId()).dedupeKey(key));
        recipientRepository.save(new NotificationRecipient(saved, w.getWriterId()));
    }

    private static WorkWarningResponse response(WorkWarning x, String ackName) {
        return new WorkWarningResponse(x.getId(), x.getWorkId(), x.getSourceType(), x.getNearMissId(), x.getSeverity(),
                x.getMessage(), x.getShownAt(), x.getAcknowledgedBy(), ackName, x.getAcknowledgedAt(), x.getAckNote());
    }
}
