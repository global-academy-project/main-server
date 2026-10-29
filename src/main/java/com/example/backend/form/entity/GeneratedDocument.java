package com.example.backend.form.entity;

import com.example.backend.common.util.JsonText;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 생성 문서: 인쇄 PDF·감사 보고서. 출력 시점 데이터를 통째로 남겨 "그날 무엇을 인쇄했나"를 재현한다.
 * 테이블 규칙은 V6 generated_documents. 수정·삭제는 V9 trg_gd_* 가 막는다 (다시 출력하면 seq 를 올려 새로 추가).
 * 만든 주체: USER = users.id, AGENT = agent_runs.id (이때 agent_run_id 도 같은 값).
 */
@Entity
@Table(name = "generated_documents")
public class GeneratedDocument {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "form_type", nullable = false, length = 30, updatable = false)
    private FormType formType;

    @Column(name = "template_id", nullable = false, updatable = false)
    private Long templateId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_type", nullable = false, length = 20, updatable = false)
    private DocumentTarget targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    /** 출력한 기록의 lock_version (저장된 판만 출력) */
    @Column(name = "source_version", nullable = false, updatable = false)
    private int sourceVersion;

    /** 같은 기록·양식의 n번째 출력 (1부터) */
    @Column(nullable = false, updatable = false)
    private int seq;

    @Column(name = "file_path", nullable = false, length = 500, updatable = false)
    private String filePath;

    @Column(name = "file_sha256", nullable = false, length = 64, updatable = false, columnDefinition = "char(64)")
    private String fileSha256;

    @Column(name = "page_count", updatable = false)
    private Integer pageCount;

    /** 출력 당시 데이터 전체 (JSON 객체) */
    @Column(name = "data_snapshot", nullable = false, updatable = false, columnDefinition = "json")
    private String dataSnapshot;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "created_by_type", nullable = false, length = 10, updatable = false)
    private CreatorType createdByType;

    @Column(name = "created_by_id", nullable = false, updatable = false)
    private Long createdById;

    @Column(name = "agent_run_id", updatable = false)
    private Long agentRunId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected GeneratedDocument() {
    }

    private GeneratedDocument(FormTemplate template, Long siteId, DocumentTarget targetType, Long targetId,
                              int sourceVersion, int seq, String filePath, String fileSha256, Integer pageCount,
                              String dataSnapshot, CreatorType createdByType, Long createdById, Long agentRunId) {
        if (template == null || template.getId() == null || siteId == null || targetType == null || targetId == null) {
            throw new IllegalArgumentException("저장된 양식·현장·원본 기록이 필요합니다.");
        }
        if (template.getSiteId() != null && !template.getSiteId().equals(siteId)) {
            throw new IllegalArgumentException("다른 현장 전용 양식입니다.");
        }
        if (sourceVersion < 0 || seq < 1) {
            throw new IllegalArgumentException("원본 판은 0 이상, 출력 순번은 1 이상입니다.");
        }
        if (filePath == null || filePath.isBlank() || filePath.length() > 500) {
            throw new IllegalArgumentException("파일 경로가 필요합니다 (500자 이내).");
        }
        if (fileSha256 == null || !SHA256.matcher(fileSha256).matches()) {
            throw new IllegalArgumentException("파일 해시는 소문자 SHA-256(64자)이어야 합니다.");
        }
        if (pageCount != null && pageCount < 1) {
            throw new IllegalArgumentException("쪽수는 1 이상입니다.");
        }
        this.siteId = siteId;
        this.formType = template.getFormType();
        this.templateId = template.getId();
        this.targetType = targetType;
        this.targetId = targetId;
        this.sourceVersion = sourceVersion;
        this.seq = seq;
        this.filePath = filePath;
        this.fileSha256 = fileSha256;
        this.pageCount = pageCount;
        this.dataSnapshot = JsonText.object(dataSnapshot, "출력 데이터");
        this.createdByType = createdByType;
        this.createdById = createdById;
        this.agentRunId = agentRunId;
    }

    /** 사람이 [PDF 출력]. seq 는 서비스가 (기존 최대 + 1) 로 정한다 */
    public static GeneratedDocument byUser(FormTemplate template, Long siteId, DocumentTarget target, Long targetId,
                                           int sourceVersion, int seq, String filePath, String fileSha256,
                                           Integer pageCount, String dataSnapshot, Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("출력한 사용자가 필요합니다.");
        }
        return new GeneratedDocument(template, siteId, target, targetId, sourceVersion, seq, filePath, fileSha256,
                pageCount, dataSnapshot, CreatorType.USER, userId, null);
    }

    /** 에이전트가 출력 (export_form 도구) */
    public static GeneratedDocument byAgent(FormTemplate template, Long siteId, DocumentTarget target, Long targetId,
                                            int sourceVersion, int seq, String filePath, String fileSha256,
                                            Integer pageCount, String dataSnapshot, Long agentRunId) {
        if (agentRunId == null) {
            throw new IllegalArgumentException("에이전트 실행 id 가 필요합니다.");
        }
        return new GeneratedDocument(template, siteId, target, targetId, sourceVersion, seq, filePath, fileSha256,
                pageCount, dataSnapshot, CreatorType.AGENT, agentRunId, agentRunId);
    }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public FormType getFormType() { return formType; }
    public Long getTemplateId() { return templateId; }
    public DocumentTarget getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public int getSourceVersion() { return sourceVersion; }
    public int getSeq() { return seq; }
    public String getFilePath() { return filePath; }
    public String getFileSha256() { return fileSha256; }
    public Integer getPageCount() { return pageCount; }
    public String getDataSnapshot() { return dataSnapshot; }
    public CreatorType getCreatedByType() { return createdByType; }
    public Long getCreatedById() { return createdById; }
    public Long getAgentRunId() { return agentRunId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
