package com.example.backend.nearmiss.service;

import com.example.backend.agent.client.NearMissTagCall;
import com.example.backend.agent.dto.AgentRunStarted;
import com.example.backend.agent.entity.AgentMode;
import com.example.backend.agent.entity.AgentRun;
import com.example.backend.agent.entity.AgentRunStatus;
import com.example.backend.agent.entity.AgentTaskType;
import com.example.backend.agent.repository.AgentRunRepository;
import com.example.backend.agent.service.AgentRunRequestedEvent;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.jpa.LockVersions;
import com.example.backend.common.type.NetworkMode;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.nearmiss.entity.NearMiss;
import com.example.backend.nearmiss.entity.NearMissStatus;
import com.example.backend.nearmiss.repository.NearMissRepository;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.WorkZone;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.site.repository.WorkZoneRepository;
import com.example.backend.tag.entity.RecordTag;
import com.example.backend.tag.entity.RecordType;
import com.example.backend.tag.entity.TagAxis;
import com.example.backend.tag.repository.RecordTagRepository;
import com.example.backend.tag.repository.TagCodeRepository;
import com.example.backend.work.repository.WorkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 아차사고 자동 태깅 (시나리오 C). 웹 계약: POST /near-misses/{id}/auto-tags {lockVersion} → 202 {runId},
 * 화면은 GET /agent/runs/{runId} 로 상태를 조회한다.
 * <ol>
 *   <li>시작: 보고자 본인 또는 승인자 이상, lockVersion 일치, 아직 사람이 태그를 저장하지 않은 보고(REPORTED)만.
 *       실행(agent_runs, NEAR_MISS_TAG, 대화 없음)을 PROCESSING 으로 저장하고 커밋 뒤 AI 서버를 부른다 (AgentProcessingListener)</li>
 *   <li>결과 저장: 그 사이 보고가 바뀌지 않았을 때만(같은 lockVersion, 여전히 REPORTED) AI 태그로 교체한다.
 *       코드표에 없는 값·CAS 체크섬이 틀린 값·축 개수 초과는 버리고 output_json 의 skipped 에 남긴다.
 *       중대도는 "아차사고(NEAR_MISS)"만 받는다 (중상·사망은 사람만 확정)</li>
 *   <li>AI 태그는 "제안"이다: 상태는 REPORTED 그대로, RAG 적재도 하지 않는다 (사람이 태그를 저장해야 확정·적재)</li>
 * </ol>
 * 모델 호출이 실패해도 AI 서버가 규칙 태그로 성공 응답한다 (A 11/18 결정). AI 서버 자체가 실패하면 실행은 FAILED 이고
 * 화면은 태그를 직접 고르도록 안내한다.
 */
@Service
public class NearMissAutoTagService {

    private static final Logger log = LoggerFactory.getLogger(NearMissAutoTagService.class);

    public static final String TARGET_TYPE = "NEAR_MISS";
    private static final String AI_SEVERITY = "NEAR_MISS";
    private static final int TITLE_MAX = 300;
    private static final int DESCRIPTION_MAX = 4000;
    private static final int ACTION_MAX = 2000;
    private static final int ZONE_MAX = 300;
    private static final int MODEL_MAX = 60;

    private final NearMissService nearMissService;
    private final NearMissRepository nearMissRepository;
    private final RecordTagRepository tagRepository;
    private final TagCodeRepository codeRepository;
    private final WorkZoneRepository zoneRepository;
    private final WorkRepository workRepository;
    private final SiteRepository siteRepository;
    private final AgentRunRepository runRepository;
    private final RecordHistoryRepository historyRepository;
    private final LockVersions lockVersions;
    private final ApplicationEventPublisher events;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final NetworkMode networkMode;
    private final Duration staleAfter;
    private final int maxPerAxis;

    public NearMissAutoTagService(NearMissService nearMissService, NearMissRepository nearMissRepository,
                                  RecordTagRepository tagRepository, TagCodeRepository codeRepository,
                                  WorkZoneRepository zoneRepository, WorkRepository workRepository,
                                  SiteRepository siteRepository, AgentRunRepository runRepository,
                                  RecordHistoryRepository historyRepository, LockVersions lockVersions,
                                  ApplicationEventPublisher events, JsonMapper jsonMapper, Clock clock,
                                  @Value("${app.agent.network-mode}") NetworkMode networkMode,
                                  @Value("${app.agent.stale-after-seconds}") long staleAfterSeconds,
                                  @Value("${app.near-miss.tags.max-per-axis}") int maxPerAxis) {
        this.nearMissService = nearMissService;
        this.nearMissRepository = nearMissRepository;
        this.tagRepository = tagRepository;
        this.codeRepository = codeRepository;
        this.zoneRepository = zoneRepository;
        this.workRepository = workRepository;
        this.siteRepository = siteRepository;
        this.runRepository = runRepository;
        this.historyRepository = historyRepository;
        this.lockVersions = lockVersions;
        this.events = events;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.networkMode = networkMode;
        this.staleAfter = Duration.ofSeconds(staleAfterSeconds);
        this.maxPerAxis = maxPerAxis;
    }

    // ------------------------------------------------------------------ 시작

    @Transactional
    public AgentRunStarted start(Long userId, Long nearMissId, int lockVersion) {
        NearMiss n = nearMissService.loadForTagging(userId, nearMissId);
        if (lockVersion != n.getLockVersion()) {
            throw new BusinessException(ErrorCode.CONFLICT_MODIFIED);
        }
        if (n.getStatus() != NearMissStatus.REPORTED) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "사람이 태그를 저장한 보고는 자동 태깅하지 않습니다. 태그 검토에서 직접 고쳐 주세요.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (runRepository.existsByTaskTypeAndTargetTypeAndTargetIdAndStatusAndCreatedAtAfter(AgentTaskType.NEAR_MISS_TAG,
                TARGET_TYPE, n.getId(), AgentRunStatus.PROCESSING, now.minus(staleAfter))) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이 보고의 자동 태깅을 이미 하고 있습니다. 끝난 뒤 다시 시도해 주세요.");
        }
        Site site = siteRepository.findById(n.getSiteId()).orElseThrow(() -> new BusinessException(ErrorCode.SITE_NOT_FOUND));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("nearMissId", n.getId());
        input.put("lockVersion", n.getLockVersion());
        AgentRun run = new AgentRun(site.getId(), userId, null, AgentTaskType.NEAR_MISS_TAG,
                AgentMode.valueOf(site.getMode().name()), networkMode, jsonMapper.writeValueAsString(input));
        run.linkTarget(TARGET_TYPE, n.getId());
        run = runRepository.saveAndFlush(run);
        events.publishEvent(new AgentRunRequestedEvent(run.getId()));
        return new AgentRunStarted(null, run.getId(), run.getStatus());
    }

    // ------------------------------------------------------------------ AI 서버 요청

    /** 실행에 묶인 아차사고로 AI 서버 요청 만들기 (커밋 뒤 리스너에서) */
    @Transactional(readOnly = true)
    public NearMissTagCall call(AgentRun run) {
        NearMiss n = nearMissRepository.findById(run.getTargetId())
                .orElseThrow(() -> new IllegalStateException("아차사고가 없습니다: " + run.getTargetId()));
        String zoneName = n.getZoneId() == null ? null
                : zoneRepository.findById(n.getZoneId()).map(WorkZone::getName).orElse(null);
        String workType = n.getWorkId() == null ? null
                : workRepository.findById(n.getWorkId()).map(w -> w.getWorkType() == null ? null : w.getWorkType().name())
                        .orElse(null);
        return new NearMissTagCall(run.getId(), n.getSiteId(), n.getId(), cut(n.getTitle(), TITLE_MAX),
                cut(n.getDescription(), DESCRIPTION_MAX), cut(n.getImmediateAction(), ACTION_MAX), cut(zoneName, ZONE_MAX),
                workType);
    }

    // ------------------------------------------------------------------ 결과 저장

    /** AI 서버 성공 응답 저장 (실행 행을 잠근 뒤, 한 트랜잭션) */
    @Transactional
    public void saveResult(Long runId, JsonNode data, int elapsedMs) {
        AgentRun run = runRepository.findForUpdate(runId).orElse(null);
        if (run == null || run.getStatus() != AgentRunStatus.PROCESSING) {
            log.warn("이미 끝났거나 없는 실행의 결과는 저장하지 않습니다. runId={}", runId);
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        NearMiss n = run.getTargetId() == null ? null : nearMissRepository.findById(run.getTargetId()).orElse(null);
        if (n == null) {
            run.fail("NEAR_MISS_NOT_FOUND: 아차사고를 찾을 수 없습니다.", elapsedMs, now);
            return;
        }
        int startedVersion = jsonMapper.readTree(run.getInputJson()).path("lockVersion").asInt(-1);
        if (n.getStatus() != NearMissStatus.REPORTED || n.getLockVersion() != startedVersion) {
            run.fail("NEAR_MISS_CHANGED: 자동 태깅 중에 보고가 바뀌어(태그 저장 등) 결과를 저장하지 않았습니다.", elapsedMs, now);
            return;
        }
        if (data == null || !data.path("tags").isArray()) {
            run.fail("AI_BAD_RESPONSE: 태그 목록이 없습니다.", elapsedMs, now);
            return;
        }

        List<RecordTag> accepted = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<TagAxis, Integer> perAxis = new EnumMap<>(TagAxis.class);
        for (JsonNode t : data.path("tags")) {
            String reason = null;
            TagAxis axis = axisOf(t.path("axis").asString(null));
            String value = t.path("value").isString() ? t.path("value").asString().trim() : null;
            BigDecimal confidence = confidenceOf(t.path("confidence"));
            if (axis == null || value == null || value.isEmpty()) {
                reason = "축 또는 값이 없음";
            } else if (axis == TagAxis.SEVERITY && !AI_SEVERITY.equals(value)) {
                reason = "중대도는 아차사고만 제안 가능 (중상·사망은 사람이 확정)";
            } else if (axis.isCoded() ? !codeRepository.existsByAxisAndCodeAndActiveTrue(axis, value)
                    : !NearMissService.isCas(value)) {
                reason = axis.isCoded() ? "코드표에 없는 값" : "CAS 형식·체크섬이 맞지 않음";
            } else if (!seen.add(axis + ":" + value)) {
                reason = "중복";
            } else if (perAxis.merge(axis, 1, Integer::sum) > (axis == TagAxis.SEVERITY ? 1 : maxPerAxis)) {
                reason = "축 개수 초과";
            }
            if (reason != null) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("axis", t.path("axis").asString(null));
                s.put("value", t.path("value").asString(null));
                s.put("reason", reason);
                skipped.add(s);
                continue;
            }
            accepted.add(RecordTag.ai(RecordType.NEAR_MISS, n.getId(), axis, value, confidence));
        }

        // REPORTED 인 보고의 태그는 지난 자동 태깅의 AI 제안뿐이다 (사람이 저장하면 TAGGED 이후) → 통째로 교체
        tagRepository.deleteAll(tagRepository.findByRecordTypeAndRecordIdOrderByAxisAscIdAsc(RecordType.NEAR_MISS, n.getId()));
        tagRepository.flush();
        tagRepository.saveAllAndFlush(accepted);
        lockVersions.bumpOnce(n, n.getId(), startedVersion, n::getLockVersion);   // 화면이 들고 있던 버전으로는 저장 못 하게

        String source = data.path("taggingSource").asString(null);
        String version = data.path("taggingVersion").asString(null);
        String model = data.path("model").isString() ? cut(data.path("model").asString(), MODEL_MAX) : null;
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("tags", accepted.stream().map(t -> t.getAxis() + ":" + t.getValue()).toList());
        after.put("taggingSource", source);
        after.put("taggingVersion", version);
        after.put("model", model);
        historyRepository.save(RecordHistory.byAgent(n.getSiteId(), HistoryTarget.NEAR_MISS, n.getId(), HistoryAction.AI_DRAFT,
                run.getId(), null, jsonMapper.writeValueAsString(after)));

        ObjectNode output = data.isObject() ? ((ObjectNode) data).deepCopy() : jsonMapper.createObjectNode();
        output.put("nearMissId", n.getId());
        output.put("savedCount", accepted.size());
        output.put("lockVersion", n.getLockVersion());
        output.set("skipped", jsonMapper.valueToTree(skipped));
        run.complete(jsonMapper.writeValueAsString(output), model, null, elapsedMs, null, null, now);
        log.info("아차사고 자동 태깅 저장. runId={} nearMissId={} saved={} skipped={} source={}",
                run.getId(), n.getId(), accepted.size(), skipped.size(), source);
    }

    // ------------------------------------------------------------------ 내부

    private static TagAxis axisOf(String s) {
        if (s == null) {
            return null;
        }
        try {
            return TagAxis.valueOf(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 0~1 이 아니거나 숫자가 아니면 null (확신도 없음) */
    private static BigDecimal confidenceOf(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        BigDecimal v = node.decimalValue().setScale(3, RoundingMode.HALF_UP);
        return v.signum() < 0 || v.compareTo(BigDecimal.ONE) > 0 ? null : v;
    }

    /** 글자(코드 포인트) 수로 자른다. 서로게이트 쌍을 가르지 않게 */
    static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.codePointCount(0, s.length()) <= max) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, max));
    }
}
