-- V7: 판단 규칙과 판정 결과 (규칙 엔진, check_rules 도구)
-- 담당: C
-- 규칙 데이터는 R__rules_seed.sql. 판정 결과(rule_evaluations, rule_violations)는 V9 트리거로 수정·삭제 차단.

CREATE TABLE rules (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    rule_code     VARCHAR(60)   NOT NULL,
    version       INT           NOT NULL DEFAULT 1,
    work_type     VARCHAR(20)   NOT NULL,
    category      VARCHAR(20)   NOT NULL,
    target        VARCHAR(100)  NOT NULL,                   -- 예: measurement.O2, form.watcher_name
    operator      VARCHAR(20)   NOT NULL,
    min_value     DECIMAL(12,3) NULL,
    min_inclusive BOOLEAN       NOT NULL DEFAULT TRUE,
    max_value     DECIMAL(12,3) NULL,
    max_inclusive BOOLEAN       NOT NULL DEFAULT FALSE,
    allowed       JSON          NULL,
    unit          VARCHAR(10)   NULL,
    severity      VARCHAR(10)   NOT NULL DEFAULT 'BLOCK',
    message       VARCHAR(300)  NOT NULL,
    action        VARCHAR(300)  NULL,
    legal_basis   VARCHAR(200)  NOT NULL,
    source_url    VARCHAR(500)  NULL,
    enabled       BOOLEAN       NOT NULL DEFAULT TRUE,
    -- 같은 rule_code에서 켜진 판은 하나만
    enabled_key   VARCHAR(60)   GENERATED ALWAYS AS (IF(enabled, rule_code, NULL)) STORED,
    created_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_rules_code_ver (rule_code, version),
    UNIQUE KEY uk_rules_enabled (enabled_key),
    KEY idx_rules_type (work_type, enabled),
    CONSTRAINT ck_rules_code     CHECK (rule_code REGEXP '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_rules_version  CHECK (version >= 1),
    CONSTRAINT ck_rules_worktype CHECK (work_type IN ('GENERAL', 'HOT', 'CONFINED', 'HEIGHT', 'ALL')),
    CONSTRAINT ck_rules_category CHECK (category IN ('MEASUREMENT', 'REQUIRED_FIELD', 'STAFFING', 'PERIOD', 'CONFLICT', 'PHOTO')),
    CONSTRAINT ck_rules_operator CHECK (operator IN ('RANGE', 'GTE', 'GT', 'LTE', 'LT', 'EQ', 'IN', 'REQUIRED')),
    CONSTRAINT ck_rules_unit     CHECK (unit IS NULL OR unit IN ('PCT', 'PPM', 'PCT_LEL', 'M', 'HOUR', 'PERSON')),
    CONSTRAINT ck_rules_severity CHECK (severity IN ('BLOCK', 'WARN')),
    -- 연산자마다 필요한 기준값이 있어야 함
    CONSTRAINT ck_rules_operands CHECK (
        (operator = 'RANGE' AND min_value IS NOT NULL AND max_value IS NOT NULL AND min_value < max_value) OR
        (operator IN ('GTE', 'GT') AND min_value IS NOT NULL AND max_value IS NULL) OR
        (operator IN ('LTE', 'LT') AND max_value IS NOT NULL AND min_value IS NULL) OR
        (operator = 'EQ' AND min_value IS NOT NULL AND max_value IS NULL) OR
        (operator = 'IN' AND allowed IS NOT NULL AND JSON_TYPE(allowed) = 'ARRAY') OR
        (operator = 'REQUIRED' AND min_value IS NULL AND max_value IS NULL)),
    -- 수치 판정이면 단위 필수
    CONSTRAINT ck_rules_unit_req CHECK (operator IN ('IN', 'REQUIRED') OR unit IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rule_evaluations (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    site_id        BIGINT      NOT NULL,
    target_type    VARCHAR(20) NOT NULL,
    target_id      BIGINT      NOT NULL,
    result         VARCHAR(10) NOT NULL,
    input_snapshot JSON        NOT NULL,
    rules_applied  INT         NOT NULL DEFAULT 0,
    agent_run_id   BIGINT      NULL,
    evaluated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_eval_target (target_type, target_id, evaluated_at),
    KEY idx_eval_site_result (site_id, result, evaluated_at),
    CONSTRAINT fk_eval_site FOREIGN KEY (site_id) REFERENCES sites (id),
    CONSTRAINT ck_eval_target  CHECK (target_type IN ('PERMIT', 'WORK', 'INSPECTION', 'RISK', 'TBM')),
    CONSTRAINT ck_eval_result  CHECK (result IN ('PASS', 'WARN', 'BLOCK')),
    CONSTRAINT ck_eval_applied CHECK (rules_applied >= 0),
    CONSTRAINT ck_eval_snapshot CHECK (JSON_TYPE(input_snapshot) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rule_violations (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    evaluation_id BIGINT       NOT NULL,
    rule_id       BIGINT       NOT NULL,
    actual_value  VARCHAR(100) NULL,
    expected      VARCHAR(100) NOT NULL,
    severity      VARCHAR(10)  NOT NULL,
    message       VARCHAR(300) NOT NULL,
    legal_basis   VARCHAR(200) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_viol_rule (evaluation_id, rule_id),        -- 한 판정에서 같은 규칙 위반은 한 번
    KEY idx_viol_rule (rule_id),
    CONSTRAINT fk_viol_eval FOREIGN KEY (evaluation_id) REFERENCES rule_evaluations (id),
    CONSTRAINT fk_viol_rule FOREIGN KEY (rule_id)       REFERENCES rules (id),
    CONSTRAINT ck_viol_severity CHECK (severity IN ('BLOCK', 'WARN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE permits
    ADD CONSTRAINT fk_permits_last_eval FOREIGN KEY (last_evaluation_id) REFERENCES rule_evaluations (id);

ALTER TABLE work_warnings
    ADD CONSTRAINT fk_wwarn_eval FOREIGN KEY (rule_evaluation_id) REFERENCES rule_evaluations (id);

ALTER TABLE permit_events
    ADD CONSTRAINT fk_pe_eval FOREIGN KEY (evaluation_id) REFERENCES rule_evaluations (id);
