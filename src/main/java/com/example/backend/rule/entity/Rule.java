package com.example.backend.rule.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

/**
 * 판단 규칙 한 판(version). 테이블 규칙은 V7 rules, 데이터는 R__rules_seed.sql.
 * 규칙은 시드로만 바꾸고 코드에서는 읽기만 한다.
 * target 은 판정 입력 안의 경로 (예: measurement.O2, form.watcher_name).
 */
@Entity
@Immutable
@Table(name = "rules")
public class Rule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_code", nullable = false, length = 60)
    private String ruleCode;

    @Column(nullable = false)
    private int version;

    /** GENERAL / HOT / CONFINED / HEIGHT / ALL */
    @Column(name = "work_type", nullable = false, length = 20)
    private String workType;

    /** MEASUREMENT / REQUIRED_FIELD / STAFFING / PERIOD / CONFLICT / PHOTO */
    @Column(nullable = false, length = 20)
    private String category;

    @Column(nullable = false, length = 100)
    private String target;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private RuleOperator operator;

    @Column(name = "min_value", precision = 12, scale = 3)
    private BigDecimal minValue;

    @Column(name = "min_inclusive", nullable = false)
    private boolean minInclusive;

    @Column(name = "max_value", precision = 12, scale = 3)
    private BigDecimal maxValue;

    @Column(name = "max_inclusive", nullable = false)
    private boolean maxInclusive;

    /** IN 연산자의 허용 값 (JSON 배열 문자열) */
    @Column(columnDefinition = "json")
    private String allowed;

    /** PCT / PPM / PCT_LEL / M / HOUR / PERSON */
    @Column(length = 10)
    private String unit;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private RuleSeverity severity;

    @Column(nullable = false, length = 300)
    private String message;

    @Column(length = 300)
    private String action;

    @Column(name = "legal_basis", nullable = false, length = 200)
    private String legalBasis;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(nullable = false)
    private boolean enabled;

    protected Rule() {
    }

    public Long getId() { return id; }
    public String getRuleCode() { return ruleCode; }
    public int getVersion() { return version; }
    public String getWorkType() { return workType; }
    public String getCategory() { return category; }
    public String getTarget() { return target; }
    public RuleOperator getOperator() { return operator; }
    public BigDecimal getMinValue() { return minValue; }
    public boolean isMinInclusive() { return minInclusive; }
    public BigDecimal getMaxValue() { return maxValue; }
    public boolean isMaxInclusive() { return maxInclusive; }
    public String getAllowed() { return allowed; }
    public String getUnit() { return unit; }
    public RuleSeverity getSeverity() { return severity; }
    public String getMessage() { return message; }
    public String getAction() { return action; }
    public String getLegalBasis() { return legalBasis; }
    public String getSourceUrl() { return sourceUrl; }
    public boolean isEnabled() { return enabled; }
}
