-- V5: 아차사고, 5축 분류 코드표와 태그, 작업 경고, 개선 조치
-- 담당: C 작성, 업무 소유 A

CREATE TABLE near_misses (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    site_id          BIGINT       NOT NULL,
    zone_id          BIGINT       NULL,
    work_id          BIGINT       NULL,
    occurred_at      DATETIME(6)  NOT NULL,
    title            VARCHAR(200) NOT NULL,
    description      TEXT         NOT NULL,
    immediate_action TEXT         NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'REPORTED',
    reporter_id      BIGINT       NOT NULL,
    lock_version     INT          NOT NULL DEFAULT 0,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_nm_id_site (id, site_id),
    KEY idx_nm_site_time (site_id, occurred_at),
    KEY idx_nm_zone_time (zone_id, occurred_at),
    CONSTRAINT fk_nm_site     FOREIGN KEY (site_id)          REFERENCES sites (id),
    CONSTRAINT fk_nm_zone     FOREIGN KEY (zone_id, site_id) REFERENCES work_zones (id, site_id),
    CONSTRAINT fk_nm_work     FOREIGN KEY (work_id, site_id) REFERENCES works (id, site_id),
    CONSTRAINT fk_nm_reporter FOREIGN KEY (reporter_id)      REFERENCES users (id),
    CONSTRAINT ck_nm_status CHECK (status IN ('REPORTED', 'TAGGED', 'REVIEWED', 'CLOSED'))
    -- "발생 시각 <= 보고 시각"은 휴대폰 시계 오차로 정상 보고가 막힐 수 있어 DB가 아닌 앱에서 검증 (5분 허용)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tag_codes (
    axis       VARCHAR(20)  NOT NULL,
    code       VARCHAR(30)  NOT NULL,
    label      VARCHAR(100) NOT NULL,
    parent     VARCHAR(30)  NULL,
    sort_order INT          NOT NULL DEFAULT 0,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (axis, code),
    CONSTRAINT ck_tc_axis CHECK (axis IN ('SEVERITY', 'ACCIDENT_TYPE', 'HAZARD', 'WORK_TYPE')),
    CONSTRAINT ck_tc_code CHECK (code REGEXP '^[A-Z][A-Z0-9_]*$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 태그. 물질(SUBSTANCE) 축은 CAS 번호, 나머지 4축은 tag_codes에 있는 코드만 허용 (생성 컬럼 + FK로 강제)
CREATE TABLE record_tags (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    record_type VARCHAR(20)  NOT NULL,
    record_id   BIGINT       NOT NULL,
    axis        VARCHAR(20)  NOT NULL,
    value       VARCHAR(30)  NOT NULL,
    code_axis   VARCHAR(20)  GENERATED ALWAYS AS (IF(axis = 'SUBSTANCE', NULL, axis)) STORED,
    source      VARCHAR(10)  NOT NULL DEFAULT 'AI',
    confidence  DECIMAL(4,3) NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tag (record_type, record_id, axis, value),
    KEY idx_tag_axis_value (axis, value),
    CONSTRAINT fk_tag_code FOREIGN KEY (code_axis, value) REFERENCES tag_codes (axis, code),
    CONSTRAINT ck_tag_record CHECK (record_type IN ('NEAR_MISS', 'WORK', 'PERMIT', 'INSPECTION', 'RISK_ITEM')),
    CONSTRAINT ck_tag_axis   CHECK (axis IN ('SEVERITY', 'ACCIDENT_TYPE', 'HAZARD', 'WORK_TYPE', 'SUBSTANCE')),
    CONSTRAINT ck_tag_cas    CHECK (axis <> 'SUBSTANCE' OR value REGEXP '^[0-9]{2,7}-[0-9]{2}-[0-9]$'),
    CONSTRAINT ck_tag_source CHECK (source IN ('AI', 'USER')),
    CONSTRAINT ck_tag_conf   CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO tag_codes (axis, code, label, sort_order) VALUES
 ('SEVERITY', 'NEAR_MISS',  '아차사고', 1),
 ('SEVERITY', 'MINOR',      '경상', 2),
 ('SEVERITY', 'SERIOUS',    '중상', 3),
 ('SEVERITY', 'FATAL',      '사망', 4),
 ('ACCIDENT_TYPE', 'FIRE',          '화재', 1),
 ('ACCIDENT_TYPE', 'EXPLOSION',     '폭발·파열', 2),
 ('ACCIDENT_TYPE', 'LEAK',          '누출·접촉', 3),
 ('ACCIDENT_TYPE', 'ASPHYXIATION',  '질식', 4),
 ('ACCIDENT_TYPE', 'FALL',          '떨어짐', 5),
 ('ACCIDENT_TYPE', 'TRIP',          '넘어짐', 6),
 ('ACCIDENT_TYPE', 'CAUGHT',        '끼임', 7),
 ('ACCIDENT_TYPE', 'STRUCK',        '맞음', 8),
 ('ACCIDENT_TYPE', 'COLLAPSE',      '무너짐', 9),
 ('ACCIDENT_TYPE', 'ELECTRIC',      '감전', 10),
 ('HAZARD', 'MECHANICAL',     '기계적', 1),
 ('HAZARD', 'ELECTRICAL',     '전기적', 2),
 ('HAZARD', 'CHEMICAL',       '화학적', 3),
 ('HAZARD', 'ENVIRONMENTAL',  '작업환경', 4),
 ('HAZARD', 'HUMAN',          '인적·관리', 5),
 ('WORK_TYPE', 'GENERAL',     '일반', 1),
 ('WORK_TYPE', 'HOT',         '화기', 2),
 ('WORK_TYPE', 'CONFINED',    '밀폐공간', 3),
 ('WORK_TYPE', 'HEIGHT',      '고소', 4),
 ('WORK_TYPE', 'ELECTRICAL',  '전기', 5),
 ('WORK_TYPE', 'LIFTING',     '중량물·양중', 6);

-- 위험성평가 발생형태도 코드표 값만 허용
ALTER TABLE risk_assessment_items
    ADD COLUMN accident_axis VARCHAR(20) GENERATED ALWAYS AS (IF(accident_type IS NULL, NULL, 'ACCIDENT_TYPE')) STORED AFTER accident_type,
    ADD CONSTRAINT fk_rai_accident FOREIGN KEY (accident_axis, accident_type) REFERENCES tag_codes (axis, code);

-- 작업에 띄운 경고와 확인 기록 (시나리오 C). "경고를 보고도 진행했나"를 감사 때 보여준다
CREATE TABLE work_warnings (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    site_id            BIGINT       NOT NULL,
    work_id            BIGINT       NOT NULL,
    source_type        VARCHAR(20)  NOT NULL,
    near_miss_id       BIGINT       NULL,
    rule_evaluation_id BIGINT       NULL,                   -- FK는 V7
    severity           VARCHAR(10)  NOT NULL DEFAULT 'WARN',
    message            VARCHAR(500) NOT NULL,
    shown_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    acknowledged_by    BIGINT       NULL,
    acknowledged_at    DATETIME(6)  NULL,
    ack_note           VARCHAR(300) NULL,                   -- 확인하며 남긴 조치 (예: 안전대 2중 체결)
    PRIMARY KEY (id),
    UNIQUE KEY uk_ww_nm (work_id, near_miss_id),            -- 같은 아차사고로 같은 작업에 경고 중복 금지
    KEY idx_wwarn_site (site_id, shown_at),
    KEY idx_wwarn_unack (work_id, acknowledged_at),
    CONSTRAINT fk_wwarn_work FOREIGN KEY (work_id, site_id)      REFERENCES works (id, site_id),
    CONSTRAINT fk_wwarn_nm   FOREIGN KEY (near_miss_id, site_id) REFERENCES near_misses (id, site_id),
    CONSTRAINT fk_wwarn_ack  FOREIGN KEY (acknowledged_by)       REFERENCES users (id),
    CONSTRAINT ck_wwarn_source CHECK (source_type IN ('NEAR_MISS', 'RULE', 'ZONE_HAZARD', 'QUALIFICATION')),
    CONSTRAINT ck_wwarn_ref    CHECK ((source_type = 'NEAR_MISS') = (near_miss_id IS NOT NULL)
                                  AND (source_type = 'RULE') = (rule_evaluation_id IS NOT NULL)),
    CONSTRAINT ck_wwarn_sev    CHECK (severity IN ('WARN', 'CRITICAL')),
    CONSTRAINT ck_wwarn_ack    CHECK ((acknowledged_by IS NULL) = (acknowledged_at IS NULL)),
    CONSTRAINT ck_wwarn_crit   CHECK (severity <> 'CRITICAL' OR acknowledged_at IS NULL OR ack_note IS NOT NULL)  -- 긴급 경고 확인은 조치 내용 필수
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 개선 조치: 아차사고·점검 불량·규칙 위반·감사 지적 이후의 조치 담당·기한·완료·확인
CREATE TABLE corrective_actions (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    site_id          BIGINT       NOT NULL,
    source_type      VARCHAR(20)  NOT NULL,
    source_id        BIGINT       NULL,
    title            VARCHAR(200) NOT NULL,
    description      TEXT         NULL,
    assignee_id      BIGINT       NOT NULL,
    due_date         DATE         NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    completed_at     DATETIME(6)  NULL,
    completion_note  TEXT         NULL,
    verified_by      BIGINT       NULL,
    verified_at      DATETIME(6)  NULL,
    created_by       BIGINT       NOT NULL,
    lock_version     INT          NOT NULL DEFAULT 0,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_ca_site_status (site_id, status, due_date),     -- 기한 지난 조치 조회
    KEY idx_ca_assignee (assignee_id, status),
    KEY idx_ca_source (source_type, source_id),
    CONSTRAINT fk_ca_site     FOREIGN KEY (site_id)     REFERENCES sites (id),
    CONSTRAINT fk_ca_assignee FOREIGN KEY (assignee_id) REFERENCES users (id),
    CONSTRAINT fk_ca_verifier FOREIGN KEY (verified_by) REFERENCES users (id),
    CONSTRAINT fk_ca_creator  FOREIGN KEY (created_by)  REFERENCES users (id),
    CONSTRAINT ck_ca_source   CHECK (source_type IN ('NEAR_MISS', 'INSPECTION', 'RULE_VIOLATION', 'AUDIT', 'OTHER')),
    CONSTRAINT ck_ca_src_id   CHECK (source_type = 'OTHER' OR source_id IS NOT NULL),
    CONSTRAINT ck_ca_status   CHECK (status IN ('OPEN', 'IN_PROGRESS', 'DONE', 'VERIFIED', 'CANCELED')),
    CONSTRAINT ck_ca_done     CHECK ((status IN ('DONE', 'VERIFIED')) = (completed_at IS NOT NULL AND completion_note IS NOT NULL)),
    CONSTRAINT ck_ca_verified CHECK ((status = 'VERIFIED') = (verified_by IS NOT NULL AND verified_at IS NOT NULL))
    -- 조치자 = 확인자 금지는 승인과 같이 현장 모드에 따라 다르다 (안내 모드는 1인 업체 허용) → 앱에서 판단
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
