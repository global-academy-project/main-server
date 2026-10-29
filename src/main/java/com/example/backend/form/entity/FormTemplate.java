package com.example.backend.form.entity;

import com.example.backend.common.util.JsonText;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 양식 정의: 양식 종류 × 판(version) × 적용 범위(회사 전체 / 현장). 테이블 규칙은 V6 form_templates.
 * <ul>
 *   <li>같은 종류·범위에 켜진(ACTIVE) 판은 하나 (V6 uk_ft_active). 새 판을 켜기 전에 옛 판을 내리고 flush 한다</li>
 *   <li>업무 테이블의 form_schema_version 값이 여기 version 과 맞아야 한다</li>
 *   <li>스키마·인쇄 템플릿은 DRAFT 에서만 고친다 (켠 뒤에 바꾸면 이미 출력한 문서와 달라짐)</li>
 * </ul>
 */
@Entity
@Table(name = "form_templates")
public class FormTemplate {

    private static final Pattern VERSION = Pattern.compile("^v[0-9]+(\\.[0-9]+)?$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "form_type", nullable = false, length = 30, updatable = false)
    private FormType formType;

    /** 예: v1, v1.2 */
    @Column(nullable = false, length = 20, updatable = false)
    private String version;

    /** null = 회사 전체 공통, 값 = 그 현장 전용 */
    @Column(name = "site_id", updatable = false)
    private Long siteId;

    /** 입력 검증용 JSON 스키마 */
    @Column(name = "json_schema", nullable = false, columnDefinition = "json")
    private String jsonSchema;

    /** 인쇄 템플릿 파일 경로 (저장소 기준) */
    @Column(name = "print_template_path", nullable = false, length = 300)
    private String printTemplatePath;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private FormTemplateStatus status = FormTemplateStatus.DRAFT;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    protected FormTemplate() {
    }

    public FormTemplate(FormType formType, String version, Long siteId, String jsonSchema,
                        String printTemplatePath, Long createdBy) {
        if (formType == null || createdBy == null) {
            throw new IllegalArgumentException("양식 종류와 만든 사람이 필요합니다.");
        }
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("판 번호는 v1, v1.2 형식이어야 합니다.");
        }
        this.formType = formType;
        this.version = version;
        this.siteId = siteId;
        this.createdBy = createdBy;
        apply(jsonSchema, printTemplatePath);
    }

    /** 스키마·인쇄 템플릿 수정. DRAFT 에서만 */
    public void revise(String jsonSchema, String printTemplatePath) {
        if (status != FormTemplateStatus.DRAFT) {
            throw new IllegalStateException("작성 중인 판만 고칠 수 있습니다. 새 판을 만드세요 (현재 " + status + ").");
        }
        apply(jsonSchema, printTemplatePath);
    }

    /** 켜기 (같은 범위의 옛 판은 서비스가 먼저 retire) */
    public void activate() {
        if (status != FormTemplateStatus.DRAFT) {
            throw new IllegalStateException("작성 중인 판만 켤 수 있습니다 (현재 " + status + ").");
        }
        this.status = FormTemplateStatus.ACTIVE;
    }

    /** 내리기. 내린 판은 다시 켜지 않는다 (필요하면 새 판) */
    public void retire() {
        if (status == FormTemplateStatus.RETIRED) {
            throw new IllegalStateException("이미 내린 판입니다.");
        }
        this.status = FormTemplateStatus.RETIRED;
    }

    public boolean isCompanyWide() {
        return siteId == null;
    }

    private void apply(String jsonSchema, String printTemplatePath) {
        if (printTemplatePath == null || printTemplatePath.isBlank() || printTemplatePath.trim().length() > 300) {
            throw new IllegalArgumentException("인쇄 템플릿 경로가 필요합니다 (300자 이내).");
        }
        this.jsonSchema = JsonText.object(jsonSchema, "JSON 스키마");
        this.printTemplatePath = printTemplatePath.trim();
    }

    public Long getId() { return id; }
    public FormType getFormType() { return formType; }
    public String getVersion() { return version; }
    public Long getSiteId() { return siteId; }
    public String getJsonSchema() { return jsonSchema; }
    public String getPrintTemplatePath() { return printTemplatePath; }
    public FormTemplateStatus getStatus() { return status; }
    public Long getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
