package com.example.backend.nearmiss.kb;

import com.example.backend.nearmiss.entity.NearMiss;
import com.example.backend.nearmiss.repository.NearMissRepository;
import com.example.backend.site.entity.WorkZone;
import com.example.backend.site.repository.WorkZoneRepository;
import com.example.backend.tag.entity.RecordTag;
import com.example.backend.tag.entity.RecordType;
import com.example.backend.tag.entity.TagAxis;
import com.example.backend.tag.entity.TagCode;
import com.example.backend.tag.repository.RecordTagRepository;
import com.example.backend.tag.repository.TagCodeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 아차사고 → kb_accidents 청크 (A 「아차사고 RAG 적재 답변」 11/04 계약).
 *  - 아차사고 1건 = 청크 1개. point_id = uuid5(NAMESPACE_URL, "NEAR_MISS:{id}:0") (ai-model scripts/init_qdrant.py point_id 와 같음)
 *  - kb_source_id·kb_chunk_id 는 업무 DB near_misses.id 다. agent_kb 의 PK 가 아니다 (검색 doc_id = NEAR_MISS:{id} 용)
 *  - 태그는 payload 최상위 severity·accident_type·hazard·work_type·cas·ghs (ai-model app/rag/filters.py 허용 키). 서버 물질 축(SUBSTANCE)은 cas
 *  - site_id 는 보고된 현장. flagged 는 항상 false (프롬프트 인젝션 의심 표시라 서버는 쓰지 않음)
 */
@Component
public class NearMissKbDocuments {

    public static final String COLLECTION = "kb_accidents";
    public static final String SOURCE_TYPE = "NEAR_MISS";

    private static final Map<TagAxis, String> PAYLOAD_KEYS = Map.of(
            TagAxis.SEVERITY, "severity", TagAxis.ACCIDENT_TYPE, "accident_type", TagAxis.HAZARD, "hazard",
            TagAxis.WORK_TYPE, "work_type", TagAxis.SUBSTANCE, "cas");
    private static final Map<TagAxis, String> AXIS_LABELS = Map.of(
            TagAxis.SEVERITY, "중대도", TagAxis.ACCIDENT_TYPE, "발생형태", TagAxis.HAZARD, "유해요인",
            TagAxis.WORK_TYPE, "작업", TagAxis.SUBSTANCE, "관련 물질(CAS)");
    private static final List<TagAxis> TEXT_ORDER = List.of(
            TagAxis.ACCIDENT_TYPE, TagAxis.WORK_TYPE, TagAxis.HAZARD, TagAxis.SUBSTANCE, TagAxis.SEVERITY);

    private final NearMissRepository nearMissRepository;
    private final RecordTagRepository tagRepository;
    private final TagCodeRepository codeRepository;
    private final WorkZoneRepository zoneRepository;

    public NearMissKbDocuments(NearMissRepository nearMissRepository, RecordTagRepository tagRepository,
                               TagCodeRepository codeRepository, WorkZoneRepository zoneRepository) {
        this.nearMissRepository = nearMissRepository;
        this.tagRepository = tagRepository;
        this.codeRepository = codeRepository;
        this.zoneRepository = zoneRepository;
    }

    /** 적재할 청크. 아차사고가 없거나 태그가 하나도 없으면 비어 있음 */
    @Transactional(readOnly = true)
    public Optional<KbIngestClient.Chunk> document(Long nearMissId) {
        NearMiss n = nearMissRepository.findById(nearMissId).orElse(null);
        if (n == null) {
            return Optional.empty();
        }
        List<RecordTag> tags = tagRepository.findByRecordTypeAndRecordIdOrderByAxisAscIdAsc(RecordType.NEAR_MISS, n.getId());
        if (tags.isEmpty()) {
            return Optional.empty();
        }
        Map<TagAxis, List<String>> values = new EnumMap<>(TagAxis.class);
        tags.forEach(t -> values.computeIfAbsent(t.getAxis(), k -> new ArrayList<>()).add(t.getValue()));
        Map<String, String> labels = new LinkedHashMap<>();
        codeRepository.findByActiveTrueOrderByAxisAscSortOrderAsc()
                .forEach(c -> labels.put(c.getAxis() + ":" + c.getCode(), c.getLabel()));
        String zoneName = n.getZoneId() == null ? null
                : zoneRepository.findById(n.getZoneId()).map(WorkZone::getName).orElse(null);
        return Optional.of(build(n.getId(), n.getSiteId(), n.getZoneId(), zoneName, n.getOccurredAt(), n.getTitle(),
                n.getDescription(), n.getImmediateAction(), values, labels));
    }

    /** 청크 만들기 (DB 없이 테스트) */
    static KbIngestClient.Chunk build(Long id, Long siteId, Long zoneId, String zoneName, LocalDateTime occurredAt,
                                      String title, String description, String immediateAction,
                                      Map<TagAxis, List<String>> values, Map<String, String> labels) {
        StringBuilder text = new StringBuilder(title.strip());
        if (description != null && !description.isBlank()) {
            text.append(". ").append(description.strip());
        }
        if (zoneName != null) {
            text.append(" 장소: ").append(zoneName).append(".");
        }
        for (TagAxis axis : TEXT_ORDER) {
            List<String> vs = values.getOrDefault(axis, List.of());
            if (!vs.isEmpty()) {
                text.append(" ").append(AXIS_LABELS.get(axis)).append(": ")
                        .append(String.join(", ", vs.stream().map(v -> labels.getOrDefault(axis + ":" + v, v)).toList()))
                        .append(".");
            }
        }
        if (immediateAction != null && !immediateAction.isBlank()) {
            text.append(" 즉시 조치: ").append(immediateAction.strip());
        }
        String body = text.toString();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kb_source_id", id);
        payload.put("kb_chunk_id", id);
        payload.put("source_type", SOURCE_TYPE);
        payload.put("site_id", siteId);
        payload.put("flagged", false);
        for (TagAxis axis : List.of(TagAxis.SEVERITY, TagAxis.ACCIDENT_TYPE, TagAxis.HAZARD, TagAxis.WORK_TYPE, TagAxis.SUBSTANCE)) {
            payload.put(PAYLOAD_KEYS.get(axis), List.copyOf(values.getOrDefault(axis, List.of())));
        }
        payload.put("ghs", List.of());
        payload.put("title", title.strip());
        payload.put("locator", "아차사고 #" + id);
        payload.put("year", occurredAt.getYear());
        payload.put("occurred_at", occurredAt.withNano(0).toString());
        if (zoneId != null) {
            payload.put("zone_id", zoneId);
        }
        payload.put("text", body);
        return new KbIngestClient.Chunk(pointId(id), body, payload);
    }

    public static String pointId(Long nearMissId) {
        return Uuid5.of(Uuid5.NAMESPACE_URL, SOURCE_TYPE + ":" + nearMissId + ":0").toString();
    }
}
