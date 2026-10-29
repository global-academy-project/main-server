package com.example.backend.form.entity;

import com.example.backend.common.util.JsonText;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 화면 임시저장: 고치는 중인 내용을 정식 기록과 분리한다. 테이블 규칙은 V6 form_drafts.
 * 흐름: 편집 → 몇 초마다 자동 임시저장(client_seq 증가) → [저장] 때 정식 기록에 반영(lock_version 확인) → 임시저장 삭제.
 * <ul>
 *   <li>기존 기록은 사람당 임시저장 1개 (V6 uk_fd_existing), 새 문서는 여러 개 가능</li>
 *   <li>기존 기록이면 편집 시작 때의 판(base_version) 필수 (V6 ck_fd_base)</li>
 *   <li>1MB 넘는 내용은 거부 (V6 ck_fd_size, 사진은 첨부로)</li>
 *   <li>마지막 저장 후 30일이 지나면 정리 대상</li>
 * </ul>
 */
@Entity
@Table(name = "form_drafts")
public class FormDraft {

    /** DB 는 JSON_STORAGE_SIZE 로 재므로 여기서는 UTF-8 길이로 먼저 거른다 */
    public static final int MAX_BYTES = 1_048_576;
    public static final int KEEP_DAYS = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_type", nullable = false, length = 20, updatable = false)
    private DraftTarget targetType;

    /** null = 아직 저장 안 된 새 문서 */
    @Column(name = "target_id", updatable = false)
    private Long targetId;

    /** 편집을 시작할 때 기록의 lock_version. 저장 시 달라졌으면 "다른 사람이 먼저 고쳤습니다" */
    @Column(name = "base_version", updatable = false)
    private Integer baseVersion;

    @Column(name = "schema_version", nullable = false, length = 20)
    private String schemaVersion;

    /** "작성 중인 문서" 목록에 보일 이름 */
    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "draft_json", nullable = false, columnDefinition = "json")
    private String draftJson;

    /** 자동 저장 순번. 늦게 도착한 옛 요청이 새 내용을 덮지 않게 */
    @Column(name = "client_seq", nullable = false)
    private int clientSeq;

    /** 만료 시각과 비교하므로 앱 시각으로 넣는다 (V6 ck_fd_expire) */
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    @Column(name = "expires_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime expiresAt;

    protected FormDraft() {
    }

    private FormDraft(Long siteId, Long userId, DraftTarget targetType, Long targetId, Integer baseVersion,
                      String schemaVersion, String title, String draftJson, LocalDateTime now) {
        if (siteId == null || userId == null || targetType == null || now == null) {
            throw new IllegalArgumentException("현장·사용자·기록 종류·시각이 필요합니다.");
        }
        if ((targetId == null) != (baseVersion == null)) {
            throw new IllegalArgumentException("기존 기록의 임시저장은 기록 id 와 기준 판이 함께 필요합니다.");
        }
        if (baseVersion != null && baseVersion < 0) {
            throw new IllegalArgumentException("기준 판은 0 이상입니다.");
        }
        if (schemaVersion == null || schemaVersion.isBlank() || schemaVersion.length() > 20) {
            throw new IllegalArgumentException("양식 판이 필요합니다 (20자 이내).");
        }
        this.siteId = siteId;
        this.userId = userId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.baseVersion = baseVersion;
        this.schemaVersion = schemaVersion;
        this.createdAt = now;
        apply(title, draftJson, now);
    }

    /** 아직 저장 안 된 새 문서 */
    public static FormDraft forNew(Long siteId, Long userId, DraftTarget target, String schemaVersion,
                                   String title, String draftJson, LocalDateTime now) {
        return new FormDraft(siteId, userId, target, null, null, schemaVersion, title, draftJson, now);
    }

    /** 기존 기록 고치는 중. baseVersion = 편집 시작 때 그 기록의 lock_version */
    public static FormDraft forExisting(Long siteId, Long userId, DraftTarget target, Long targetId, int baseVersion,
                                        String schemaVersion, String title, String draftJson, LocalDateTime now) {
        if (targetId == null) {
            throw new IllegalArgumentException("기록 id 가 필요합니다.");
        }
        return new FormDraft(siteId, userId, target, targetId, baseVersion, schemaVersion, title, draftJson, now);
    }

    /**
     * 자동 저장. 순번이 지금보다 크지 않으면(늦게 도착한 옛 요청) 무시하고 false.
     * 동시에 여러 요청이 오면 엔티티 대신 저장소의 조건부 UPDATE (FormDraftRepository.autosave) 를 쓴다.
     */
    public boolean autosave(int seq, String title, String draftJson, LocalDateTime now) {
        if (seq <= clientSeq) {
            return false;
        }
        apply(title, draftJson, now);
        this.clientSeq = seq;
        return true;
    }

    /** 정식 저장 때 판 비교. 다르면 다른 사람이 먼저 고친 것 */
    public boolean isStale(int currentVersion) {
        return baseVersion != null && baseVersion != currentVersion;
    }

    public boolean isExpired(LocalDateTime now) {
        return now != null && !now.isBefore(expiresAt);
    }

    private void apply(String title, String draftJson, LocalDateTime now) {
        if (title == null || title.isBlank() || title.trim().length() > 200) {
            throw new IllegalArgumentException("제목이 필요합니다 (200자 이내).");
        }
        String json = JsonText.object(draftJson, "임시저장 내용");
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("임시저장 내용이 1MB 를 넘습니다. 사진은 첨부로 올려 주세요.");
        }
        this.title = title.trim();
        this.draftJson = json;
        this.expiresAt = now.plusDays(KEEP_DAYS);
    }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public Long getUserId() { return userId; }
    public DraftTarget getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public Integer getBaseVersion() { return baseVersion; }
    public String getSchemaVersion() { return schemaVersion; }
    public String getTitle() { return title; }
    public String getDraftJson() { return draftJson; }
    public int getClientSeq() { return clientSeq; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
}
