package com.example.backend.risk.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 위험성평가의 위험요인 한 줄 (작업 단계 → 유해·위험요인 → 빈도·강도 → 대책 → 개선 후 빈도·강도).
 * 테이블 규칙은 V3 risk_assessment_items. 점수(risk_score, residual_score)는 DB 생성 컬럼이라 읽기만.
 * 평가가 DRAFT 일 때만 추가·수정·삭제 가능 (V9 trg_rai_lock_*) → 서비스에서 평가 상태를 먼저 확인.
 */
@Entity
@Table(name = "risk_assessment_items")
public class RiskAssessmentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assessment_id", nullable = false, updatable = false)
    private Long assessmentId;

    /** 평가 안 순번 (1부터) */
    @Column(nullable = false)
    private int seq;

    @Column(name = "work_step", nullable = false, length = 100)
    private String workStep;

    @Column(nullable = false, length = 300)
    private String hazard;

    /** tag_codes(ACCIDENT_TYPE) 코드. 예: FALL, FIRE (DB FK 없음 → 서비스에서 확인) */
    @Column(name = "accident_type", length = 30)
    private String accidentType;

    @Column(nullable = false, columnDefinition = "tinyint")
    private int frequency;

    @Column(nullable = false, columnDefinition = "tinyint")
    private int severity;

    /** frequency × severity (DB 생성) */
    @Column(name = "risk_score", insertable = false, updatable = false)
    private Integer riskScore;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "risk_level", nullable = false, length = 10)
    private RiskLevel riskLevel;

    @Column(nullable = false, columnDefinition = "text")
    private String controls;

    /** 대책 후 빈도 (강도와 함께 있거나 함께 없음) */
    @Column(name = "residual_frequency", columnDefinition = "tinyint")
    private Integer residualFrequency;

    @Column(name = "residual_severity", columnDefinition = "tinyint")
    private Integer residualSeverity;

    /** residual_frequency × residual_severity (DB 생성) */
    @Column(name = "residual_score", insertable = false, updatable = false)
    private Integer residualScore;

    protected RiskAssessmentItem() {
    }

    public RiskAssessmentItem(Long assessmentId, int seq, String workStep, String hazard, String accidentType,
                              int frequency, int severity, RiskLevel riskLevel, String controls,
                              Integer residualFrequency, Integer residualSeverity) {
        if (assessmentId == null) {
            throw new IllegalArgumentException("위험성평가가 필요합니다.");
        }
        this.assessmentId = assessmentId;
        update(seq, workStep, hazard, accidentType, frequency, severity, riskLevel, controls,
                residualFrequency, residualSeverity);
    }

    public void update(int seq, String workStep, String hazard, String accidentType,
                       int frequency, int severity, RiskLevel riskLevel, String controls,
                       Integer residualFrequency, Integer residualSeverity) {
        if (seq < 1) {
            throw new IllegalArgumentException("순번은 1부터입니다.");
        }
        if (isBlank(workStep) || isBlank(hazard) || isBlank(controls) || riskLevel == null) {
            throw new IllegalArgumentException("작업 단계·유해위험요인·등급·대책이 필요합니다.");
        }
        checkScale(frequency, "빈도");
        checkScale(severity, "강도");
        if ((residualFrequency == null) != (residualSeverity == null)) {
            throw new IllegalArgumentException("개선 후 빈도와 강도는 함께 넣어야 합니다.");
        }
        if (residualFrequency != null) {
            checkScale(residualFrequency, "개선 후 빈도");
            checkScale(residualSeverity, "개선 후 강도");
        }
        this.seq = seq;
        this.workStep = workStep.trim();
        this.hazard = hazard.trim();
        this.accidentType = accidentType;
        this.frequency = frequency;
        this.severity = severity;
        this.riskLevel = riskLevel;
        this.controls = controls.trim();
        this.residualFrequency = residualFrequency;
        this.residualSeverity = residualSeverity;
    }

    /** DB 생성 값과 같은 계산 (저장 전에도 쓸 수 있게) */
    public int score() {
        return frequency * severity;
    }

    private static void checkScale(int v, String label) {
        if (v < 1 || v > 5) {
            throw new IllegalArgumentException(label + "는 1~5 입니다.");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public Long getId() { return id; }
    public Long getAssessmentId() { return assessmentId; }
    public int getSeq() { return seq; }
    public String getWorkStep() { return workStep; }
    public String getHazard() { return hazard; }
    public String getAccidentType() { return accidentType; }
    public int getFrequency() { return frequency; }
    public int getSeverity() { return severity; }
    public Integer getRiskScore() { return riskScore; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public String getControls() { return controls; }
    public Integer getResidualFrequency() { return residualFrequency; }
    public Integer getResidualSeverity() { return residualSeverity; }
    public Integer getResidualScore() { return residualScore; }
}
