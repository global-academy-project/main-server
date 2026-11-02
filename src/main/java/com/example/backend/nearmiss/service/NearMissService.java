package com.example.backend.nearmiss.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.jpa.LockVersions;
import com.example.backend.history.entity.HistoryAction;
import com.example.backend.history.entity.HistoryTarget;
import com.example.backend.history.entity.RecordHistory;
import com.example.backend.history.repository.RecordHistoryRepository;
import com.example.backend.nearmiss.dto.IncidentSearchRequest;
import com.example.backend.nearmiss.dto.NearMissResponse;
import com.example.backend.nearmiss.dto.ReportNearMissRequest;
import com.example.backend.nearmiss.dto.SaveTagsRequest;
import com.example.backend.nearmiss.dto.TagCodeResponse;
import com.example.backend.nearmiss.entity.NearMiss;
import com.example.backend.nearmiss.repository.NearMissRepository;
import com.example.backend.site.entity.WorkZone;
import com.example.backend.site.repository.WorkZoneRepository;
import com.example.backend.site.service.SiteAccessService;
import com.example.backend.site.service.SiteGuard;
import com.example.backend.tag.entity.RecordTag;
import com.example.backend.tag.entity.RecordType;
import com.example.backend.tag.entity.TagAxis;
import com.example.backend.tag.entity.TagCode;
import com.example.backend.tag.entity.TagSource;
import com.example.backend.tag.repository.RecordTagRepository;
import com.example.backend.tag.repository.TagCodeRepository;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import com.example.backend.work.repository.WorkRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 아차사고 보고·조회·태그 검토·관련 사고 검색 (A 웹 v3.1.1 7절 초안, 서버 의견으로 확정).
 * <ul>
 *   <li>보고는 현장 권한이면 누구나. 발생 시각은 현장 현지 시각(Asia/Seoul)</li>
 *   <li>태그 저장은 보고자 본인 또는 그 현장 승인자 이상. 전체 교체, 저장마다 lockVersion 증가</li>
 *   <li>SEVERITY 는 0~1개, 나머지 축은 축마다 yaml app.near-miss.tags.max-per-axis 개까지</li>
 *   <li>코드 축은 사용 중인 코드표 값만, 물질은 CAS 형식 + 체크섬</li>
 *   <li>사용자 API 로 새 AI 태그를 만들 수 없다. AI 로 보낸 항목은 저장된 AI 태그와 같아야 한다</li>
 * </ul>
 */
@Service
public class NearMissService {

    private static final Pattern CAS = Pattern.compile("^\\d{2,7}-\\d{2}-\\d$");
    private static final Set<String> TAG_EDITORS = Set.of("APPROVER", "SITE_ADMIN", "ADMIN");

    private final NearMissRepository nearMissRepository;
    private final RecordTagRepository tagRepository;
    private final TagCodeRepository codeRepository;
    private final WorkZoneRepository zoneRepository;
    private final WorkRepository workRepository;
    private final UserRepository userRepository;
    private final RecordHistoryRepository historyRepository;
    private final SiteGuard siteGuard;
    private final SiteAccessService siteAccessService;
    private final LockVersions lockVersions;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final int maxPerAxis;
    private final int listMaxRows;
    private final int searchDefaultLimit;
    private final int searchMaxLimit;
    private final int searchScanRows;

    public NearMissService(NearMissRepository nearMissRepository, RecordTagRepository tagRepository,
                           TagCodeRepository codeRepository, WorkZoneRepository zoneRepository,
                           WorkRepository workRepository, UserRepository userRepository,
                           RecordHistoryRepository historyRepository, SiteGuard siteGuard,
                           SiteAccessService siteAccessService, LockVersions lockVersions, JsonMapper jsonMapper,
                           Clock clock,
                           @Value("${app.near-miss.tags.max-per-axis}") int maxPerAxis,
                           @Value("${app.near-miss.list.max-rows}") int listMaxRows,
                           @Value("${app.near-miss.search.default-limit}") int searchDefaultLimit,
                           @Value("${app.near-miss.search.max-limit}") int searchMaxLimit,
                           @Value("${app.near-miss.search.scan-rows}") int searchScanRows) {
        this.nearMissRepository = nearMissRepository;
        this.tagRepository = tagRepository;
        this.codeRepository = codeRepository;
        this.zoneRepository = zoneRepository;
        this.workRepository = workRepository;
        this.userRepository = userRepository;
        this.historyRepository = historyRepository;
        this.siteGuard = siteGuard;
        this.siteAccessService = siteAccessService;
        this.lockVersions = lockVersions;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.maxPerAxis = maxPerAxis;
        this.listMaxRows = listMaxRows;
        this.searchDefaultLimit = searchDefaultLimit;
        this.searchMaxLimit = searchMaxLimit;
        this.searchScanRows = searchScanRows;
    }

    /** 사용 중인 태그 코드 (축·순서대로). 물질(CAS)은 코드표가 없다 */
    @Transactional(readOnly = true)
    public List<TagCodeResponse> tagCodes() {
        return codeRepository.findByActiveTrueOrderByAxisAscSortOrderAsc().stream()
                .map(c -> new TagCodeResponse(c.getAxis(), c.getCode(), c.getLabel())).toList();
    }

    @Transactional
    public NearMissResponse report(Long userId, ReportNearMissRequest r) {
        User user = siteGuard.requireAccess(userId, r.siteId());
        if (r.zoneId() != null && zoneRepository.findByIdAndSiteId(r.zoneId(), r.siteId()).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이 현장의 구역이 아닙니다: " + r.zoneId());
        }
        if (r.workId() != null && workRepository.findById(r.workId()).filter(w -> w.getSiteId().equals(r.siteId())).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이 현장의 작업이 아닙니다: " + r.workId());
        }
        NearMiss n;
        try {
            n = new NearMiss(r.siteId(), r.zoneId(), r.workId(), r.occurredAt(), r.title(), r.description(),
                    blank(r.immediateAction()), user.getId(), LocalDateTime.now(clock));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, e.getMessage());
        }
        nearMissRepository.saveAndFlush(n);
        history(n, HistoryAction.CREATE, user.getId(), Map.of("title", n.getTitle()));
        return responses(List.of(n)).get(0);
    }

    @Transactional(readOnly = true)
    public NearMissResponse get(Long userId, Long nearMissId) {
        return responses(List.of(load(userId, nearMissId))).get(0);
    }

    /** 현장 아차사고 (최근 발생 순, yaml app.near-miss.list.max-rows 까지) */
    @Transactional(readOnly = true)
    public List<NearMissResponse> list(Long userId, Long siteId) {
        siteGuard.requireAccess(userId, siteId);
        return responses(nearMissRepository.findBySiteIdOrderByOccurredAtDesc(siteId, PageRequest.of(0, listMaxRows))
                .getContent());
    }

    @Transactional
    public NearMissResponse saveTags(Long userId, Long nearMissId, SaveTagsRequest r) {
        NearMiss n = load(userId, nearMissId);
        User user = userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        boolean editor = n.getReporterId().equals(user.getId())
                || siteAccessService.roleAt(user, n.getSiteId()).filter(TAG_EDITORS::contains).isPresent();
        if (!editor) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "태그는 보고자 본인이나 승인자 이상만 저장할 수 있습니다.");
        }
        if (r.lockVersion() != n.getLockVersion()) {
            throw new BusinessException(ErrorCode.CONFLICT_MODIFIED);
        }
        List<RecordTag> saved = tagRepository.findByRecordTypeAndRecordIdOrderByAxisAscIdAsc(RecordType.NEAR_MISS, n.getId());
        Map<String, RecordTag> savedByKey = saved.stream().collect(Collectors.toMap(t -> key(t.getAxis(), t.getValue()), t -> t));
        Map<TagAxis, Integer> perAxis = new EnumMap<>(TagAxis.class);
        Map<String, SaveTagsRequest.Tag> wanted = new LinkedHashMap<>();
        for (SaveTagsRequest.Tag t : r.tags()) {
            String value = t.value().trim();
            String k = key(t.axis(), value);
            if (wanted.containsKey(k)) {
                throw bad("같은 태그가 두 번 있습니다: " + t.axis() + " " + value);
            }
            checkValue(t.axis(), value);
            int count = perAxis.merge(t.axis(), 1, Integer::sum);
            if (t.axis() == TagAxis.SEVERITY && count > 1) {
                throw bad("중대도(SEVERITY)는 하나만 고를 수 있습니다.");
            }
            if (count > maxPerAxis) {
                throw bad(t.axis() + " 태그는 " + maxPerAxis + "개까지입니다.");
            }
            if (t.source() == TagSource.USER && t.confidence() != null) {
                throw bad("사용자 태그에는 확신도를 넣지 않습니다: " + t.axis() + " " + value);
            }
            if (t.source() == TagSource.AI) {
                RecordTag prev = savedByKey.get(k);
                if (prev == null || prev.getSource() != TagSource.AI || !sameConfidence(prev.getConfidence(), t.confidence())) {
                    throw bad("AI 태그는 새로 만들거나 바꿀 수 없습니다. 고친 태그는 source USER 로 보내 주세요: "
                            + t.axis() + " " + value);
                }
            }
            wanted.put(k, new SaveTagsRequest.Tag(t.axis(), value, t.source(), t.confidence()));
        }
        // 그대로 둘 행은 두고(같은 축·값·출처), 나머지는 지우고 새로 넣는다
        List<RecordTag> remove = new ArrayList<>();
        Set<String> keep = new HashSet<>();
        for (RecordTag t : saved) {
            SaveTagsRequest.Tag w = wanted.get(key(t.getAxis(), t.getValue()));
            if (w != null && w.source() == t.getSource()) {
                keep.add(key(t.getAxis(), t.getValue()));
            } else {
                remove.add(t);
            }
        }
        tagRepository.deleteAll(remove);
        tagRepository.flush();
        List<RecordTag> add = new ArrayList<>();
        for (Map.Entry<String, SaveTagsRequest.Tag> e : wanted.entrySet()) {
            if (!keep.contains(e.getKey())) {
                add.add(RecordTag.user(RecordType.NEAR_MISS, n.getId(), e.getValue().axis(), e.getValue().value()));
            }
        }
        tagRepository.saveAllAndFlush(add);
        try {
            if (!wanted.isEmpty()) {
                n.markTagged();   // 보고 직후(REPORTED)면 TAGGED 로. 검토 이후 상태는 그대로
            }
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_STATE, e.getMessage());
        }
        nearMissRepository.saveAndFlush(n);
        lockVersions.bumpOnce(n, n.getId(), r.lockVersion(), n::getLockVersion);
        history(n, HistoryAction.UPDATE, user.getId(), Map.of("tags", wanted.values().stream()
                .map(t -> t.axis() + ":" + t.value() + ":" + t.source()).toList()));
        return responses(List.of(n)).get(0);
    }

    /** 관련 사고 검색: 현장(·구역) 안에서 최근 발생 순으로 훑고 태그·글자 조건으로 거른다 */
    @Transactional(readOnly = true)
    public List<NearMissResponse> search(Long userId, IncidentSearchRequest r) {
        siteGuard.requireAccess(userId, r.siteId());
        int limit = Math.min(r.limit() == null ? searchDefaultLimit : r.limit(), searchMaxLimit);
        String q = r.query() == null || r.query().isBlank() ? null : "%" + escapeLike(r.query().trim().toLowerCase()) + "%";
        List<NearMiss> candidates = nearMissRepository.search(r.siteId(), r.zoneId(), q, PageRequest.of(0, searchScanRows));
        Map<TagAxis, Set<String>> filters = filters(r.filters());
        if (filters.isEmpty()) {
            return responses(candidates.stream().limit(limit).toList());
        }
        Map<Long, List<RecordTag>> tags = tagsOf(candidates.stream().map(NearMiss::getId).toList());
        List<NearMiss> hits = new ArrayList<>();
        for (NearMiss n : candidates) {
            List<RecordTag> mine = tags.getOrDefault(n.getId(), List.of());
            boolean ok = filters.entrySet().stream().allMatch(f -> mine.stream()
                    .anyMatch(t -> t.getAxis() == f.getKey() && f.getValue().contains(t.getValue())));
            if (ok) {
                hits.add(n);
                if (hits.size() >= limit) break;
            }
        }
        return responses(hits);
    }

    // ------------------------------------------------------------------ 내부

    private NearMiss load(Long userId, Long nearMissId) {
        NearMiss n = nearMissRepository.findById(nearMissId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "아차사고를 찾을 수 없습니다."));
        siteGuard.requireAccess(userId, n.getSiteId());
        return n;
    }

    private void checkValue(TagAxis axis, String value) {
        if (axis.isCoded()) {
            if (!codeRepository.existsByAxisAndCodeAndActiveTrue(axis, value)) {
                throw bad("코드표에 없는 값입니다: " + axis + " " + value);
            }
        } else if (!isCas(value)) {
            throw bad("CAS 번호 형식이나 체크섬이 맞지 않습니다: " + value);
        }
    }

    /** CAS 번호: 형식 + 체크섬 (마지막 자리 = 앞 숫자를 뒤에서부터 1,2,3… 곱한 합 mod 10) */
    static boolean isCas(String value) {
        if (value == null || !CAS.matcher(value).matches()) {
            return false;
        }
        String digits = value.replace("-", "");
        int check = digits.charAt(digits.length() - 1) - '0';
        int sum = 0;
        for (int i = digits.length() - 2, w = 1; i >= 0; i--, w++) {
            sum += (digits.charAt(i) - '0') * w;
        }
        return sum % 10 == check;
    }

    private Map<TagAxis, Set<String>> filters(IncidentSearchRequest.Filters f) {
        Map<TagAxis, Set<String>> m = new EnumMap<>(TagAxis.class);
        if (f == null) {
            return m;
        }
        put(m, TagAxis.SEVERITY, f.severity());
        put(m, TagAxis.ACCIDENT_TYPE, f.accidentType());
        put(m, TagAxis.HAZARD, f.hazard());
        put(m, TagAxis.WORK_TYPE, f.workType());
        put(m, TagAxis.SUBSTANCE, f.cas());
        return m;
    }

    private static void put(Map<TagAxis, Set<String>> m, TagAxis axis, List<String> values) {
        if (values == null) return;
        Set<String> s = values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).collect(Collectors.toSet());
        if (!s.isEmpty()) m.put(axis, s);
    }

    private Map<Long, List<RecordTag>> tagsOf(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return tagRepository.findByRecordTypeAndRecordIdIn(RecordType.NEAR_MISS, ids).stream()
                .collect(Collectors.groupingBy(RecordTag::getRecordId));
    }

    private List<NearMissResponse> responses(List<NearMiss> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<RecordTag>> tags = tagsOf(rows.stream().map(NearMiss::getId).toList());
        Map<String, String> labels = new HashMap<>();
        for (TagCode c : codeRepository.findAll()) {
            labels.put(key(c.getAxis(), c.getCode()), c.getLabel());
        }
        Set<Long> zoneIds = rows.stream().map(NearMiss::getZoneId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> zones = zoneRepository.findAllById(zoneIds).stream()
                .collect(Collectors.toMap(WorkZone::getId, WorkZone::getName));
        Map<Long, String> users = userRepository.findAllById(rows.stream().map(NearMiss::getReporterId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(User::getId, User::getName));
        return rows.stream().map(n -> new NearMissResponse(n.getId(), n.getSiteId(), n.getZoneId(),
                n.getZoneId() == null ? null : zones.get(n.getZoneId()), n.getWorkId(), n.getTitle(), n.getOccurredAt(),
                n.getDescription(), n.getImmediateAction(), n.getStatus(), n.getReporterId(), users.get(n.getReporterId()),
                n.getCreatedAt(), n.getLockVersion(),
                tags.getOrDefault(n.getId(), List.of()).stream()
                        .sorted(java.util.Comparator.comparing(RecordTag::getAxis).thenComparing(RecordTag::getId))
                        .map(t -> new NearMissResponse.Tag(t.getAxis(), t.getValue(), labels.get(key(t.getAxis(), t.getValue())),
                                t.getSource(), t.getConfidence()))
                        .toList())).toList();
    }

    private static boolean sameConfidence(BigDecimal saved, BigDecimal sent) {
        if (saved == null || sent == null) {
            return saved == null && sent == null;
        }
        return saved.setScale(3, RoundingMode.HALF_UP).compareTo(sent.setScale(3, RoundingMode.HALF_UP)) == 0;
    }

    private static String key(TagAxis axis, String value) {
        return axis + ":" + value;
    }

    private static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");   // JPQL escape '!' (MySQL 은 \\ 가 문자열 이스케이프라 피함)
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }

    private void history(NearMiss n, HistoryAction action, Long userId, Map<String, Object> after) {
        historyRepository.save(RecordHistory.byUser(n.getSiteId(), HistoryTarget.NEAR_MISS, n.getId(), action, userId, null,
                jsonMapper.writeValueAsString(after)));
    }
}
