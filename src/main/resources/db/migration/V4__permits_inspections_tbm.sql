-- V4: 고위험 작업허가(측정·체크·연장·중단·재개), 장비·구역 점검, TBM(다룬 작업·참석자), 첨부 사진과 사진 확인
-- 담당: C (작업허가·점검·사진), TBM은 B 업무

-- 반려 후 재신청은 revision을 올린 새 행. 차단(BLOCKED)은 같은 행에서 재측정 후 다시 판정.
-- 승인 후의 변화(연장·중단·재개·인수인계·완료 확인)는 permit_events에 쌓는다. 허가서 본문(valid_to 등)은 고치지 않는다.
--  실제 종료 시각 = 승인된 마지막 EXTEND 이벤트의 new_valid_to, 없으면 valid_to
CREATE TABLE permits (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    site_id             BIGINT       NOT NULL,
    work_id             BIGINT       NOT NULL,
    permit_type         VARCHAR(20)  NOT NULL,
    revision            INT          NOT NULL DEFAULT 1,
    permit_no           VARCHAR(40)  NOT NULL,              -- 예: HOT-20261026-001 (revision마다 같은 번호 + 판 구분)
    valid_from          DATETIME(6)  NOT NULL,
    valid_to            DATETIME(6)  NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    watcher_name        VARCHAR(50)  NULL,                  -- 화재감시자(화기) / 감시인(밀폐)
    supervisor_name     VARCHAR(50)  NULL,
    form_schema_version VARCHAR(20)  NOT NULL DEFAULT 'v1',
    form_data           JSON         NULL,
    last_evaluation_id  BIGINT       NULL,                  -- FK는 V7
    writer_id           BIGINT       NOT NULL,
    agent_run_id        BIGINT       NULL,                  -- FK는 V8
    closed_at           DATETIME(6)  NULL,
    lock_version        INT          NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_permits_no_rev (site_id, permit_no, revision),
    UNIQUE KEY uk_permits_work_type_rev (work_id, permit_type, revision),
    UNIQUE KEY uk_permits_id_site (id, site_id),
    KEY idx_permits_site_status (site_id, status, valid_from),
    CONSTRAINT fk_permits_site   FOREIGN KEY (site_id)          REFERENCES sites (id),
    CONSTRAINT fk_permits_work   FOREIGN KEY (work_id, site_id) REFERENCES works (id, site_id),
    CONSTRAINT fk_permits_writer FOREIGN KEY (writer_id)        REFERENCES users (id),
    CONSTRAINT ck_permits_type   CHECK (permit_type IN ('HOT', 'CONFINED', 'HEIGHT')),
    -- SUSPENDED: 작업 중 측정값 이상·기상 악화 등으로 중단 (재측정·재개 승인 후 APPROVED로 복귀)
    CONSTRAINT ck_permits_status CHECK (status IN ('DRAFT', 'CHECKING', 'BLOCKED', 'PENDING', 'APPROVED', 'SUSPENDED', 'REJECTED', 'CLOSED', 'SUPERSEDED')),
    CONSTRAINT ck_permits_period CHECK (valid_to > valid_from),
    CONSTRAINT ck_permits_rev    CHECK (revision >= 1),
    CONSTRAINT ck_permits_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))   -- 완료 상태와 완료 시각은 항상 같이
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 가스 측정값. 단계가 셋이다:
--   PRE_WORK  : 작업 전 (허가 신청 단계. 허가가 DRAFT·CHECKING·BLOCKED일 때)
--   DURING    : 작업 중 주기 측정 (밀폐공간 등. 허가가 APPROVED·SUSPENDED일 때) → 기준 미달이면 규칙 엔진이 중단(SUSPEND) 처리
--   POST_WORK : 작업 후 (완료 확인용. 허가가 APPROVED일 때)
CREATE TABLE permit_measurements (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    permit_id     BIGINT        NOT NULL,
    phase         VARCHAR(10)   NOT NULL DEFAULT 'PRE_WORK',
    measured_at   DATETIME(6)   NOT NULL,
    item          VARCHAR(20)   NOT NULL,
    value         DECIMAL(10,3) NOT NULL,
    unit          VARCHAR(10)   NOT NULL,
    location      VARCHAR(100)  NULL,
    measurer_name VARCHAR(50)   NULL,
    instrument    VARCHAR(100)  NULL,                       -- 측정기 이름 (설비 등록 안 된 측정기)
    instrument_equipment_id BIGINT NULL,                    -- 등록된 측정기면 연결 → 교정일(next_inspection_date) 지난 측정기 판정 가능
    is_remeasure  BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_pm_permit (permit_id, item, measured_at),        -- "항목별 최신 측정값" 조회
    KEY idx_pm_phase (permit_id, phase, measured_at),
    CONSTRAINT fk_pm_permit FOREIGN KEY (permit_id) REFERENCES permits (id) ON DELETE CASCADE,
    CONSTRAINT fk_pm_instrument FOREIGN KEY (instrument_equipment_id) REFERENCES equipment (id),
    CONSTRAINT ck_pm_phase CHECK (phase IN ('PRE_WORK', 'DURING', 'POST_WORK')),
    CONSTRAINT ck_pm_item  CHECK (item IN ('O2', 'LEL', 'H2S', 'CO', 'CO2', 'NH3', 'CL2', 'OTHER')),
    CONSTRAINT ck_pm_unit  CHECK (unit IN ('PCT', 'PPM', 'PCT_LEL')),
    CONSTRAINT ck_pm_value CHECK (value >= 0 AND (unit = 'PPM' OR value <= 100)),
    -- 항목과 단위 짝: 산소·탄산가스는 %, 가연성 가스는 %LEL, 독성 가스는 ppm
    CONSTRAINT ck_pm_item_unit CHECK (
        (item IN ('O2', 'CO2') AND unit = 'PCT') OR
        (item = 'LEL' AND unit = 'PCT_LEL') OR
        (item IN ('H2S', 'CO', 'NH3', 'CL2') AND unit = 'PPM') OR
        (item = 'OTHER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE permit_checks (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    permit_id  BIGINT       NOT NULL,
    item_code  VARCHAR(50)  NOT NULL,
    label      VARCHAR(200) NOT NULL,
    checked    BOOLEAN      NOT NULL DEFAULT FALSE,
    note       VARCHAR(300) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pc_item (permit_id, item_code),
    CONSTRAINT fk_pc_permit FOREIGN KEY (permit_id) REFERENCES permits (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 승인 후 허가에 일어나는 일. 연장은 승인이 필요하다(approvals target_type = 'PERMIT_EVENT').
-- 결정된 이벤트는 고칠 수 없다 (V9 트리거)
CREATE TABLE permit_events (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    permit_id     BIGINT       NOT NULL,
    site_id       BIGINT       NOT NULL,
    event_type    VARCHAR(20)  NOT NULL,
    reason        VARCHAR(500) NOT NULL,
    new_valid_to  DATETIME(6)  NULL,                        -- EXTEND일 때 연장 후 종료 시각
    handover_to   VARCHAR(50)  NULL,                        -- HANDOVER일 때 인수자 (교대)
    status        VARCHAR(10)  NOT NULL DEFAULT 'REQUESTED',
    requested_by  BIGINT       NOT NULL,
    decided_by    BIGINT       NULL,
    decided_at    DATETIME(6)  NULL,
    evaluation_id BIGINT       NULL,                        -- 측정값 이상으로 자동 중단됐다면 그 판정 (FK는 V7)
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_pe_permit (permit_id, created_at),
    KEY idx_pe_site (site_id, event_type, created_at),
    CONSTRAINT fk_pe_permit  FOREIGN KEY (permit_id, site_id) REFERENCES permits (id, site_id),
    CONSTRAINT fk_pe_req     FOREIGN KEY (requested_by)       REFERENCES users (id),
    CONSTRAINT fk_pe_decider FOREIGN KEY (decided_by)         REFERENCES users (id),
    CONSTRAINT ck_pe_type     CHECK (event_type IN ('EXTEND', 'SUSPEND', 'RESUME', 'HANDOVER', 'CLOSE_CHECK')),
    CONSTRAINT ck_pe_status   CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_pe_extend   CHECK ((event_type = 'EXTEND') = (new_valid_to IS NOT NULL)),
    CONSTRAINT ck_pe_handover CHECK ((event_type = 'HANDOVER') = (handover_to IS NOT NULL)),
    CONSTRAINT ck_pe_decided  CHECK ((status = 'REQUESTED') = (decided_at IS NULL) AND (decided_at IS NULL) = (decided_by IS NULL))
    -- 연장 시각이 현재 종료 시각보다 뒤인지, 허가 상태가 맞는지는 V9 트리거가 확인
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inspections (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    site_id       BIGINT      NOT NULL,
    target_type   VARCHAR(20) NOT NULL,
    equipment_id  BIGINT      NULL,
    zone_id       BIGINT      NULL,
    work_id       BIGINT      NULL,
    inspect_date  DATE        NOT NULL,
    template_code VARCHAR(50) NOT NULL,
    result        VARCHAR(10) NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    form_data     JSON        NULL,
    inspector_id  BIGINT      NOT NULL,
    lock_version  INT         NOT NULL DEFAULT 0,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_insp_site_date (site_id, inspect_date),
    KEY idx_insp_zone (zone_id, inspect_date),
    KEY idx_insp_equipment (equipment_id, inspect_date),
    CONSTRAINT fk_insp_site      FOREIGN KEY (site_id)               REFERENCES sites (id),
    CONSTRAINT fk_insp_equipment FOREIGN KEY (equipment_id, site_id) REFERENCES equipment (id, site_id),
    CONSTRAINT fk_insp_zone      FOREIGN KEY (zone_id, site_id)      REFERENCES work_zones (id, site_id),
    CONSTRAINT fk_insp_work      FOREIGN KEY (work_id, site_id)      REFERENCES works (id, site_id),
    CONSTRAINT fk_insp_inspector FOREIGN KEY (inspector_id)          REFERENCES users (id),
    CONSTRAINT ck_insp_target    CHECK (target_type IN ('EQUIPMENT', 'ZONE')),
    CONSTRAINT ck_insp_target_id CHECK ((target_type = 'EQUIPMENT' AND equipment_id IS NOT NULL)
                                     OR (target_type = 'ZONE' AND zone_id IS NOT NULL)),
    CONSTRAINT ck_insp_result    CHECK (result IS NULL OR result IN ('PASS', 'FAIL', 'PARTIAL')),
    CONSTRAINT ck_insp_status    CHECK (status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_insp_done      CHECK (status = 'DRAFT' OR result IS NOT NULL)   -- 제출 이후엔 결과 필수
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inspection_items (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    inspection_id BIGINT       NOT NULL,
    seq           INT          NOT NULL,
    item_code     VARCHAR(50)  NOT NULL,
    question      VARCHAR(300) NOT NULL,
    result        VARCHAR(5)   NULL,
    note          VARCHAR(300) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ii_seq (inspection_id, seq),
    UNIQUE KEY uk_ii_code (inspection_id, item_code),
    CONSTRAINT fk_ii_inspection FOREIGN KEY (inspection_id) REFERENCES inspections (id) ON DELETE CASCADE,
    CONSTRAINT ck_ii_seq    CHECK (seq >= 1),
    CONSTRAINT ck_ii_result CHECK (result IS NULL OR result IN ('OK', 'NG', 'NA')),
    CONSTRAINT ck_ii_ng_note CHECK (result IS NULL OR result <> 'NG' OR note IS NOT NULL)  -- 불량이면 내용 필수
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- TBM 1회 = 한 팀이 모여 하는 회의. 보통 여러 작업을 함께 다룬다 → 다룬 작업은 tbm_works
CREATE TABLE tbm_records (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    site_id          BIGINT       NOT NULL,
    team_id          BIGINT       NULL,
    zone_id          BIGINT       NULL,                     -- 실시 장소
    tbm_date         DATE         NOT NULL,
    held_at          DATETIME(6)  NULL,
    leader_name      VARCHAR(50)  NULL,
    attendee_count   INT          NULL,                     -- 현장 인원 수 (참석자 명단과 별개로 사진 인원 비교용)
    education_points JSON         NULL,
    health_check     VARCHAR(500) NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    writer_id        BIGINT       NOT NULL,
    agent_run_id     BIGINT       NULL,
    lock_version     INT          NOT NULL DEFAULT 0,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tbm_id_site (id, site_id),
    UNIQUE KEY uk_tbm_id_site_date (id, site_id, tbm_date),  -- tbm_works 복합 FK 대상
    KEY idx_tbm_site_date (site_id, tbm_date),
    CONSTRAINT fk_tbm_site   FOREIGN KEY (site_id)          REFERENCES sites (id),
    CONSTRAINT fk_tbm_team   FOREIGN KEY (team_id)          REFERENCES teams (id),
    CONSTRAINT fk_tbm_zone   FOREIGN KEY (zone_id, site_id) REFERENCES work_zones (id, site_id),
    CONSTRAINT fk_tbm_writer FOREIGN KEY (writer_id)        REFERENCES users (id),
    CONSTRAINT ck_tbm_status CHECK (status IN ('DRAFT', 'DONE', 'APPROVED')),
    CONSTRAINT ck_tbm_count  CHECK (attendee_count IS NULL OR attendee_count >= 0),
    CONSTRAINT ck_tbm_done   CHECK (status = 'DRAFT' OR (held_at IS NOT NULL AND attendee_count IS NOT NULL))  -- 실시 완료면 시각·인원 필수
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- TBM에서 다룬 작업. 같은 현장 작업만, 작업 하나는 하루에 TBM 한 번 (tbm_date를 같이 들고 UNIQUE)
CREATE TABLE tbm_works (
    tbm_id     BIGINT      NOT NULL,
    work_id    BIGINT      NOT NULL,
    site_id    BIGINT      NOT NULL,
    tbm_date   DATE        NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tbm_id, work_id),
    UNIQUE KEY uk_tw_work_date (work_id, tbm_date),
    CONSTRAINT fk_tw_tbm  FOREIGN KEY (tbm_id, site_id, tbm_date) REFERENCES tbm_records (id, site_id, tbm_date) ON DELETE CASCADE ON UPDATE CASCADE,  -- TBM 날짜를 고치면 같이 바뀜
    CONSTRAINT fk_tw_work FOREIGN KEY (work_id, site_id)          REFERENCES works (id, site_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- TBM 참석자. 그 현장에 배치된 작업자만 (worker_sites). 서명은 signatures(V6)에 남는다
CREATE TABLE tbm_attendees (
    tbm_id     BIGINT      NOT NULL,
    worker_id  BIGINT      NOT NULL,
    site_id    BIGINT      NOT NULL,
    health_ok  BOOLEAN     NULL,                            -- 작업 전 건강 상태 확인 (NULL = 확인 안 함)
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tbm_id, worker_id),
    KEY idx_ta_worker (worker_id),
    CONSTRAINT fk_ta_tbm    FOREIGN KEY (tbm_id, site_id)    REFERENCES tbm_records (id, site_id) ON DELETE CASCADE,
    CONSTRAINT fk_ta_worker FOREIGN KEY (worker_id, site_id) REFERENCES worker_sites (worker_id, site_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 첨부 공용 (다형 참조라 owner FK 없음 → owner_type을 CHECK로 막고 앱에서 존재 확인)
CREATE TABLE attachments (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    site_id       BIGINT       NOT NULL,
    owner_type    VARCHAR(20)  NOT NULL,
    owner_id      BIGINT       NOT NULL,
    kind          VARCHAR(10)  NOT NULL DEFAULT 'PHOTO',
    original_name VARCHAR(255) NOT NULL,
    stored_path   VARCHAR(500) NOT NULL,
    mime_type     VARCHAR(100) NULL,
    file_size     BIGINT       NULL,
    sha256        CHAR(64)     NULL,                        -- 같은 사진 재사용(어제 TBM 사진 재제출) 탐지
    taken_at      DATETIME(6)  NULL,
    uploaded_by   BIGINT       NOT NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_att_path (stored_path),
    KEY idx_att_owner (owner_type, owner_id),
    KEY idx_att_hash (site_id, sha256),
    CONSTRAINT fk_att_site FOREIGN KEY (site_id)     REFERENCES sites (id),
    CONSTRAINT fk_att_user FOREIGN KEY (uploaded_by) REFERENCES users (id),
    -- DRAFT: 임시저장 중 올린 사진. 최종 저장 때 실제 기록으로 owner를 옮기고, 버려진 임시저장의 사진은 정리 배치가 지운다
    CONSTRAINT ck_att_owner CHECK (owner_type IN ('WORK', 'PERMIT', 'INSPECTION', 'TBM', 'NEAR_MISS', 'WORKER', 'CORRECTIVE_ACTION', 'SIGNATURE', 'DRAFT')),
    CONSTRAINT ck_att_kind  CHECK (kind IN ('PHOTO', 'DRAWING', 'PDF')),
    CONSTRAINT ck_att_size  CHECK (file_size IS NULL OR file_size >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE photo_checks (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    attachment_id   BIGINT      NOT NULL,
    check_type      VARCHAR(10) NOT NULL,
    detected_people INT         NULL,
    detected_date   DATE        NULL,
    zone_match      BOOLEAN     NULL,
    duplicate_of    BIGINT      NULL,                       -- 같은 해시의 이전 첨부
    result          VARCHAR(10) NOT NULL,
    detail          JSON        NULL,
    model           VARCHAR(50) NULL,
    agent_run_id    BIGINT      NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_photo_att (attachment_id, created_at),
    CONSTRAINT fk_photo_att FOREIGN KEY (attachment_id) REFERENCES attachments (id),
    CONSTRAINT fk_photo_dup FOREIGN KEY (duplicate_of)  REFERENCES attachments (id),
    CONSTRAINT ck_photo_type   CHECK (check_type IN ('TBM', 'ZONE')),
    CONSTRAINT ck_photo_result CHECK (result IN ('PASS', 'FAIL', 'UNSURE')),
    CONSTRAINT ck_photo_people CHECK (detected_people IS NULL OR detected_people >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 자격 증빙 사진 연결 (V2에서 미뤄둔 FK)
ALTER TABLE worker_qualifications
    ADD CONSTRAINT fk_wq_proof FOREIGN KEY (proof_attachment_id) REFERENCES attachments (id);
