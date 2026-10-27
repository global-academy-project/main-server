-- V3: 당일 작업(취급 물질·배치 인원), 위험성평가(일일·최초·정기·수시)
-- 담당: C 작성, 업무 소유 B

CREATE TABLE works (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    site_id       BIGINT       NOT NULL,
    zone_id       BIGINT       NULL,
    work_date     DATE         NOT NULL,
    title         VARCHAR(200) NOT NULL,
    description   TEXT         NULL,
    work_type     VARCHAR(20)  NOT NULL DEFAULT 'GENERAL',
    is_high_risk  BOOLEAN      NOT NULL DEFAULT FALSE,
    start_at      DATETIME(6)  NULL,
    end_at        DATETIME(6)  NULL,
    worker_count  INT          NULL,
    team_id       BIGINT       NULL,                        -- 작업 소속 (teams)
    status        VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    writer_id     BIGINT       NOT NULL,
    agent_run_id  BIGINT       NULL,                        -- FK는 V8
    lock_version  INT          NOT NULL DEFAULT 0,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_works_id_site (id, site_id),
    KEY idx_works_site_date (site_id, work_date, status),
    KEY idx_works_zone_date (zone_id, work_date),
    CONSTRAINT fk_works_site   FOREIGN KEY (site_id)          REFERENCES sites (id),
    CONSTRAINT fk_works_zone   FOREIGN KEY (zone_id, site_id) REFERENCES work_zones (id, site_id),
    CONSTRAINT fk_works_writer FOREIGN KEY (writer_id)        REFERENCES users (id),
    CONSTRAINT fk_works_team   FOREIGN KEY (team_id)          REFERENCES teams (id),
    CONSTRAINT ck_works_type    CHECK (work_type IN ('GENERAL', 'HOT', 'CONFINED', 'HEIGHT')),
    CONSTRAINT ck_works_status  CHECK (status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'BLOCKED', 'IN_PROGRESS', 'DONE', 'CANCELED')),
    CONSTRAINT ck_works_period  CHECK (start_at IS NULL OR end_at IS NULL OR end_at > start_at),
    CONSTRAINT ck_works_workers CHECK (worker_count IS NULL OR worker_count > 0),
    CONSTRAINT ck_works_highrisk CHECK (work_type = 'GENERAL' OR is_high_risk = TRUE)   -- 고위험 유형이면 플래그도 TRUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE work_risk_factors (
    work_id     BIGINT      NOT NULL,
    factor      VARCHAR(20) NOT NULL,
    detected_by VARCHAR(10) NOT NULL DEFAULT 'AGENT',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (work_id, factor),
    CONSTRAINT fk_wrf_work FOREIGN KEY (work_id) REFERENCES works (id) ON DELETE CASCADE,
    CONSTRAINT ck_wrf_factor CHECK (factor IN ('HOT', 'CONFINED', 'HEIGHT')),
    CONSTRAINT ck_wrf_by     CHECK (detected_by IN ('AGENT', 'USER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 작업에서 다루는 물질. JSON으로 두면 "지난달 톨루엔 다룬 작업" 같은 조회·태깅이 어렵다
CREATE TABLE work_materials (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    work_id    BIGINT        NOT NULL,
    cas_no     VARCHAR(20)   NULL,
    name       VARCHAR(200)  NOT NULL,
    quantity   DECIMAL(14,3) NULL,
    unit       VARCHAR(10)   NULL,
    created_at DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_wm_name (work_id, name),
    KEY idx_wm_cas (cas_no),
    CONSTRAINT fk_wm_work FOREIGN KEY (work_id) REFERENCES works (id) ON DELETE CASCADE,
    CONSTRAINT ck_wm_cas  CHECK (cas_no IS NULL OR cas_no REGEXP '^[0-9]{2,7}-[0-9]{2}-[0-9]$'),
    CONSTRAINT ck_wm_qty  CHECK ((quantity IS NULL) = (unit IS NULL) AND (quantity IS NULL OR quantity >= 0)),
    CONSTRAINT ck_wm_unit CHECK (unit IS NULL OR unit IN ('KG', 'L', 'TON', 'M3'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 작업 배치 인원 (누가 이 작업을 하나). 그 현장에 배치된 작업자만 (worker_sites)
CREATE TABLE work_workers (
    work_id      BIGINT      NOT NULL,
    worker_id    BIGINT      NOT NULL,
    site_id      BIGINT      NOT NULL,
    role_in_work VARCHAR(20) NOT NULL DEFAULT 'WORKER',
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (work_id, worker_id),
    KEY idx_ww_worker (worker_id),
    CONSTRAINT fk_ww_work   FOREIGN KEY (work_id, site_id)   REFERENCES works (id, site_id) ON DELETE CASCADE,
    CONSTRAINT fk_ww_worker FOREIGN KEY (worker_id, site_id) REFERENCES worker_sites (worker_id, site_id),
    CONSTRAINT ck_ww_role CHECK (role_in_work IN ('LEADER', 'WORKER', 'FIRE_WATCHER', 'ATTENDANT', 'SUPERVISOR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 위험성평가. 종류가 넷이다 (위험성평가 지침):
--   DAILY   : 당일 작업 단위 (작업 필수). 시나리오 A의 아침 등록 항목
--   INITIAL : 사업장 최초 평가 / REGULAR : 정기(연 1회 등) / ADHOC : 수시(설비·공정 변경, 사고 후)
--   → 공정·구역 단위라 작업 없이 존재. 일일 평가는 기존 정기·수시 평가를 참고해 만든다(base_assessment_id)
-- 반려 후 다시 쓰면 revision을 올린 새 행. 이전 판은 그대로 남는다.
CREATE TABLE risk_assessments (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    site_id             BIGINT       NOT NULL,
    assessment_type     VARCHAR(10)  NOT NULL DEFAULT 'DAILY',
    work_id             BIGINT       NULL,
    zone_id             BIGINT       NULL,                  -- 정기·수시 평가의 대상 구역 (공정)
    title               VARCHAR(200) NULL,                  -- 정기·수시 평가 이름 (예: 2026 반응공정 정기 위험성평가)
    base_assessment_id  BIGINT       NULL,                  -- 일일 평가가 참고한 정기·수시 평가
    revision            INT          NOT NULL DEFAULT 1,
    assess_date         DATE        NOT NULL,
    method              VARCHAR(20) NOT NULL DEFAULT 'FREQ_SEVERITY',
    status              VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    form_schema_version VARCHAR(20) NOT NULL DEFAULT 'v1',
    form_data           JSON        NULL,
    writer_id           BIGINT      NOT NULL,
    agent_run_id        BIGINT      NULL,
    lock_version        INT         NOT NULL DEFAULT 0,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_risk_work_rev (work_id, revision),
    KEY idx_risk_site_date (site_id, assess_date),
    KEY idx_risk_zone (zone_id, assessment_type, assess_date),
    CONSTRAINT fk_risk_site   FOREIGN KEY (site_id)          REFERENCES sites (id),
    CONSTRAINT fk_risk_work   FOREIGN KEY (work_id, site_id) REFERENCES works (id, site_id),
    CONSTRAINT fk_risk_zone   FOREIGN KEY (zone_id, site_id) REFERENCES work_zones (id, site_id),
    CONSTRAINT fk_risk_base   FOREIGN KEY (base_assessment_id) REFERENCES risk_assessments (id),
    CONSTRAINT fk_risk_writer FOREIGN KEY (writer_id)        REFERENCES users (id),
    CONSTRAINT ck_risk_type   CHECK (assessment_type IN ('DAILY', 'INITIAL', 'REGULAR', 'ADHOC')),
    CONSTRAINT ck_risk_daily  CHECK (assessment_type <> 'DAILY' OR work_id IS NOT NULL),        -- 일일 평가는 작업 필수
    CONSTRAINT ck_risk_title  CHECK (assessment_type = 'DAILY' OR title IS NOT NULL),           -- 정기·수시 평가는 이름 필수
    CONSTRAINT ck_risk_base   CHECK (base_assessment_id IS NULL OR assessment_type = 'DAILY'),  -- 참고는 일일 평가만
    CONSTRAINT ck_risk_method CHECK (method IN ('FREQ_SEVERITY', 'CHECKLIST', 'THREE_STEP')),
    CONSTRAINT ck_risk_status CHECK (status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED')),
    CONSTRAINT ck_risk_rev    CHECK (revision >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE risk_assessment_items (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    assessment_id      BIGINT       NOT NULL,
    seq                INT          NOT NULL,
    work_step          VARCHAR(100) NOT NULL,
    hazard             VARCHAR(300) NOT NULL,
    accident_type      VARCHAR(30)  NULL,                   -- tag_codes(ACCIDENT_TYPE) 코드
    frequency          TINYINT      NOT NULL,
    severity           TINYINT      NOT NULL,
    risk_score         INT GENERATED ALWAYS AS (frequency * severity) STORED,
    risk_level         VARCHAR(10)  NOT NULL,
    controls           TEXT         NOT NULL,
    residual_frequency TINYINT      NULL,
    residual_severity  TINYINT      NULL,
    residual_score     INT GENERATED ALWAYS AS (residual_frequency * residual_severity) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rai_seq (assessment_id, seq),
    CONSTRAINT fk_rai_assessment FOREIGN KEY (assessment_id) REFERENCES risk_assessments (id) ON DELETE CASCADE,
    CONSTRAINT ck_rai_seq   CHECK (seq >= 1),
    CONSTRAINT ck_rai_freq  CHECK (frequency BETWEEN 1 AND 5),
    CONSTRAINT ck_rai_sev   CHECK (severity BETWEEN 1 AND 5),
    CONSTRAINT ck_rai_level CHECK (risk_level IN ('HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_rai_res_pair CHECK ((residual_frequency IS NULL) = (residual_severity IS NULL)),  -- 둘 다 있거나 둘 다 없음
    CONSTRAINT ck_rai_res_freq CHECK (residual_frequency IS NULL OR residual_frequency BETWEEN 1 AND 5),
    CONSTRAINT ck_rai_res_sev  CHECK (residual_severity IS NULL OR residual_severity BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
