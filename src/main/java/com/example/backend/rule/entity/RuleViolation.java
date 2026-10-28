package com.example.backend.rule.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 판정에서 어긋난 규칙 하나. 테이블 규칙은 V7 rule_violations. 추가만 한다 (V9 트리거).
 * 문구·근거 조항을 복사해 두므로, 나중에 규칙 데이터가 바뀌어도 그때 보여준 내용이 그대로 남는다.
 */
@Entity
@Table(name = "rule_violations")
public class RuleViolation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "evaluation_id", nullable = false, updatable = false)
    private Long evaluationId;

    @Column(name = "rule_id", nullable = false, updatable = false)
    private Long ruleId;

    @Column(name = "actual_value", updatable = false, length = 100)
    private String actualValue;

    @Column(nullable = false, updatable = false, length = 100)
    private String expected;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 10)
    private RuleSeverity severity;

    @Column(nullable = false, updatable = false, length = 300)
    private String message;

    @Column(name = "legal_basis", nullable = false, updatable = false, length = 200)
    private String legalBasis;

    protected RuleViolation() {
    }

    public RuleViolation(Long evaluationId, Long ruleId, String actualValue, String expected,
                         RuleSeverity severity, String message, String legalBasis) {
        this.evaluationId = evaluationId;
        this.ruleId = ruleId;
        this.actualValue = actualValue;
        this.expected = expected;
        this.severity = severity;
        this.message = message;
        this.legalBasis = legalBasis;
    }

    public Long getId() { return id; }
    public Long getEvaluationId() { return evaluationId; }
    public Long getRuleId() { return ruleId; }
    public String getActualValue() { return actualValue; }
    public String getExpected() { return expected; }
    public RuleSeverity getSeverity() { return severity; }
    public String getMessage() { return message; }
    public String getLegalBasis() { return legalBasis; }
}
