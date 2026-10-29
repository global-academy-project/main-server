package com.example.backend.agent.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 근거 인용 (추가만). 테이블 규칙은 V8 evidence_citations.
 *  - 지식 데이터(사고사례·지침)는 AI 서버 쪽 DB·Qdrant 에 있어 외래키 대신 kb_collection + kb_point_id 로 느슨하게 참조하고,
 *    원본이 바뀌어도 남도록 snippet(인용 당시 원문)을 반드시 남긴다
 *  - 규칙 근거(RULE)는 rule_id 필수
 * 만들 때는 출처별 팩토리(fromKb, fromRule, of)를 쓴다.
 */
@Entity
@Table(name = "evidence_citations")
public class EvidenceCitation {

    /** Qdrant 점 id = UUID v5 (DB ck_ev_kb_uuid) */
    private static final Pattern KB_POINT_ID =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private Long runId;

    @Column(name = "step_id", updatable = false)
    private Long stepId;

    /** 이 근거가 뒷받침하는 업무 기록 (예: RISK, 3) */
    @Column(name = "target_type", length = 20)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source_type", nullable = false, updatable = false, length = 20)
    private EvidenceSourceType sourceType;

    /** kb_accidents | kb_guides */
    @Column(name = "kb_collection", updatable = false, length = 40)
    private String kbCollection;

    @Column(name = "kb_point_id", updatable = false, columnDefinition = "char(36)")
    private String kbPointId;

    @Column(name = "rule_id", updatable = false)
    private Long ruleId;

    @Column(nullable = false, updatable = false, length = 300)
    private String title;

    /** 문서 안 위치 (예: "7.2(1)", "제618조") */
    @Column(updatable = false, length = 100)
    private String locator;

    @Column(updatable = false, length = 500)
    private String url;

    /** 인용 당시 원문 */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(updatable = false, columnDefinition = "text")
    private String snippet;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected EvidenceCitation() {
    }

    private EvidenceCitation(Long runId, Long stepId, EvidenceSourceType sourceType, String kbCollection,
                             String kbPointId, Long ruleId, String title, String locator, String url, String snippet) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("근거 제목이 필요합니다.");
        }
        if (sourceType.requiresKbChunk() && kbPointId == null) {
            throw new IllegalArgumentException(sourceType + " 근거는 어느 청크인지(kbPointId)가 필요합니다.");
        }
        if ((kbCollection == null) != (kbPointId == null)) {
            throw new IllegalArgumentException("kbCollection 과 kbPointId 는 함께 있어야 합니다.");
        }
        if (kbPointId != null && (!KB_POINT_ID.matcher(kbPointId).matches() || snippet == null)) {
            throw new IllegalArgumentException("kbPointId 는 UUID v5 이고 snippet(원문)이 필요합니다.");
        }
        if (sourceType == EvidenceSourceType.RULE && ruleId == null) {
            throw new IllegalArgumentException("규칙 근거는 ruleId 가 필요합니다.");
        }
        this.runId = runId;
        this.stepId = stepId;
        this.sourceType = sourceType;
        this.kbCollection = kbCollection;
        this.kbPointId = kbPointId;
        this.ruleId = ruleId;
        this.title = title;
        this.locator = locator;
        this.url = url;
        this.snippet = snippet;
    }

    /** 검색 문서(사고사례·지침 등) 근거 */
    public static EvidenceCitation fromKb(Long runId, Long stepId, EvidenceSourceType sourceType, String kbCollection,
                                          String kbPointId, String title, String locator, String url, String snippet) {
        return new EvidenceCitation(runId, stepId, sourceType, kbCollection, kbPointId, null, title, locator, url, snippet);
    }

    /** 규칙 판정 근거 (rules.id) */
    public static EvidenceCitation fromRule(Long runId, Long stepId, Long ruleId, String title, String locator,
                                            String url, String snippet) {
        return new EvidenceCitation(runId, stepId, EvidenceSourceType.RULE, null, null, ruleId, title, locator, url, snippet);
    }

    /** 그 밖의 근거 (LAW, MSDS, NEAR_MISS, RECORD) */
    public static EvidenceCitation of(Long runId, Long stepId, EvidenceSourceType sourceType, String title,
                                      String locator, String url, String snippet) {
        return new EvidenceCitation(runId, stepId, sourceType, null, null, null, title, locator, url, snippet);
    }

    /** 뒷받침하는 업무 기록 연결 (type 과 id 는 함께) */
    public EvidenceCitation forTarget(String targetType, Long targetId) {
        if ((targetType == null) != (targetId == null)) {
            throw new IllegalArgumentException("targetType 과 targetId 는 함께 있어야 합니다.");
        }
        this.targetType = targetType;
        this.targetId = targetId;
        return this;
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public Long getStepId() { return stepId; }
    public String getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public EvidenceSourceType getSourceType() { return sourceType; }
    public String getKbCollection() { return kbCollection; }
    public String getKbPointId() { return kbPointId; }
    public Long getRuleId() { return ruleId; }
    public String getTitle() { return title; }
    public String getLocator() { return locator; }
    public String getUrl() { return url; }
    public String getSnippet() { return snippet; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
