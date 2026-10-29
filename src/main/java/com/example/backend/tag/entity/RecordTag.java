package com.example.backend.tag.entity;

import com.example.backend.site.entity.SiteChemical;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 기록에 붙인 5축 태그. 테이블 규칙은 V5 record_tags.
 * 물질(SUBSTANCE) 축은 CAS 번호, 나머지 4축은 tag_codes 코드만 (DB가 생성 컬럼 code_axis + 외래키로 강제).
 * 대상 기록(record_type, record_id)은 다형 참조라 DB 외래키가 없다 → 서비스에서 존재 확인.
 * 사람이 고치면 AI 태그를 지우고 USER 태그를 새로 넣는다 (수정 대신 교체).
 */
@Entity
@Table(name = "record_tags")
public class RecordTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "record_type", nullable = false, length = 20, updatable = false)
    private RecordType recordType;

    @Column(name = "record_id", nullable = false, updatable = false)
    private Long recordId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20, updatable = false)
    private TagAxis axis;

    /** 코드(예: FIRE) 또는 CAS 번호(SUBSTANCE) */
    @Column(nullable = false, length = 30, updatable = false)
    private String value;

    /** DB 생성 컬럼 (SUBSTANCE 면 NULL). 읽기만 */
    @Column(name = "code_axis", length = 20, insertable = false, updatable = false)
    private String codeAxis;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10, updatable = false)
    private TagSource source = TagSource.AI;

    /** AI 분류 확신도 0~1 (사람이 붙이면 null) */
    @Column(precision = 4, scale = 3, updatable = false)
    private BigDecimal confidence;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected RecordTag() {
    }

    public RecordTag(RecordType recordType, Long recordId, TagAxis axis, String value, TagSource source,
                     BigDecimal confidence) {
        if (recordType == null || recordId == null || axis == null) {
            throw new IllegalArgumentException("기록 종류·기록·축이 필요합니다.");
        }
        if (value == null || value.isBlank() || value.length() > 30) {
            throw new IllegalArgumentException("태그 값이 필요합니다 (30자 이내).");
        }
        if (axis == TagAxis.SUBSTANCE && !SiteChemical.CAS_NO.matcher(value).matches()) {
            throw new IllegalArgumentException("물질 태그는 CAS 번호입니다 (예: 108-88-3).");
        }
        if (confidence != null && (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("확신도는 0~1 입니다.");
        }
        this.recordType = recordType;
        this.recordId = recordId;
        this.axis = axis;
        this.value = value;
        this.source = source == null ? TagSource.AI : source;
        this.confidence = confidence;
    }

    public static RecordTag ai(RecordType type, Long recordId, TagAxis axis, String value, BigDecimal confidence) {
        return new RecordTag(type, recordId, axis, value, TagSource.AI, confidence);
    }

    public static RecordTag user(RecordType type, Long recordId, TagAxis axis, String value) {
        return new RecordTag(type, recordId, axis, value, TagSource.USER, null);
    }

    public Long getId() { return id; }
    public RecordType getRecordType() { return recordType; }
    public Long getRecordId() { return recordId; }
    public TagAxis getAxis() { return axis; }
    public String getValue() { return value; }
    public String getCodeAxis() { return codeAxis; }
    public TagSource getSource() { return source; }
    public BigDecimal getConfidence() { return confidence; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
