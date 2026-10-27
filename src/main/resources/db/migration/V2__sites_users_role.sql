-- V2: 현장·작업구역·설비, 사용자 등급·현장별 권한, 로그인 세션, 소속·작업자·현장 배치·자격, 현장 취급 물질
-- 담당: C
--
-- 공통 규칙 (V2~V9 전체)
--  * 업무 데이터는 지우지 않는다. 취소·비활성은 status로 처리한다. FK 기본값은 삭제 금지(RESTRICT)이고,
--    부모 없이는 의미가 없는 자식 행(항목·측정값·메시지 등)만 CASCADE를 쓴다.
--  * 코드 값은 모두 CHECK로 막는다. Java enum과 값이 같아야 한다 (README 코드표).
--  * 현장이 다른 기록끼리 연결되지 않도록 (id, site_id) 복합 FK를 쓴다.
--  * 여러 사람이 고치는 테이블에는 lock_version(JPA @Version)을 둔다.
--
-- 배포 단위: 회사마다 시스템을 따로 설치한다 (시스템 1개 = 회사 1개). 그래서 회사 테이블은 없다.
-- 권한 구조
--  users.role = 계정 등급:  ADMIN(이 회사의 모든 현장) | MEMBER(site_members에 있는 현장만)
--  site_members.role = 현장별 역할: WRITER | APPROVER | SITE_ADMIN   ← 대시보드 현장 드롭다운의 원천

CREATE TABLE sites (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    name                 VARCHAR(100) NOT NULL,
    mode                 VARCHAR(10)  NOT NULL DEFAULT 'VERIFY',
    industry             VARCHAR(50)  NULL,
    employee_count       INT          NULL,
    address              VARCHAR(255) NULL,
    -- 현장 운영 설정 (마감 알림·경고 기준)
    tbm_deadline         TIME         NOT NULL DEFAULT '09:00:00',   -- 이 시각까지 TBM 기록 없으면 DEADLINE 알림
    daily_log_deadline   TIME         NOT NULL DEFAULT '18:00:00',   -- 일일 안전일지 마감
    approval_due_minutes INT          NOT NULL DEFAULT 60,           -- 승인 요청 후 이 시간 지나면 승인자에게 재알림
    near_miss_warn_days  INT          NOT NULL DEFAULT 30,           -- 같은 구역 아차사고를 며칠 전까지 경고에 쓸지 (시나리오 C)
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sites_name (name),
    CONSTRAINT ck_sites_mode     CHECK (mode IN ('VERIFY', 'GUIDE')),
    CONSTRAINT ck_sites_employee CHECK (employee_count IS NULL OR employee_count >= 0),
    CONSTRAINT ck_sites_appr_due CHECK (approval_due_minutes BETWEEN 5 AND 1440),
    CONSTRAINT ck_sites_nm_days  CHECK (near_miss_warn_days BETWEEN 1 AND 365),
    CONSTRAINT ck_sites_deadline CHECK (tbm_deadline < daily_log_deadline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE work_zones (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    site_id     BIGINT       NOT NULL,
    code        VARCHAR(30)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    zone_type   VARCHAR(30)  NULL,
    is_confined BOOLEAN      NOT NULL DEFAULT FALSE,
    hazard_note VARCHAR(500) NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_zones_site_code (site_id, code),
    UNIQUE KEY uk_zones_id_site (id, site_id),
    CONSTRAINT fk_zones_site FOREIGN KEY (site_id) REFERENCES sites (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE equipment (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    site_id              BIGINT       NOT NULL,
    zone_id              BIGINT       NULL,
    code                 VARCHAR(30)  NOT NULL,
    name                 VARCHAR(100) NOT NULL,
    equipment_type       VARCHAR(30)  NOT NULL,             -- 예: CRANE, LIFT, WELDER, GAS_DETECTOR
    next_inspection_date DATE         NULL,                 -- 측정기면 다음 교정일
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_equipment_site_code (site_id, code),
    UNIQUE KEY uk_equipment_id_site (id, site_id),
    CONSTRAINT fk_equipment_site FOREIGN KEY (site_id) REFERENCES sites (id),
    CONSTRAINT fk_equipment_zone FOREIGN KEY (zone_id, site_id) REFERENCES work_zones (id, site_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- users: 컬럼 추가 → 기존 행 정리 → 제약 추가 (기존 미니 프로젝트 사용자가 있어도 실패하지 않게)
ALTER TABLE users
    ADD COLUMN role                VARCHAR(20)  NOT NULL DEFAULT 'MEMBER' AFTER name,
    ADD COLUMN phone               VARCHAR(20)  NULL AFTER role,
    ADD COLUMN department          VARCHAR(100) NULL AFTER phone,
    ADD COLUMN job_title           VARCHAR(50)  NULL AFTER department,
    ADD COLUMN status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING' AFTER job_title,
    ADD COLUMN last_site_id        BIGINT       NULL,      -- 대시보드 드롭다운에서 마지막으로 고른 현장 (다음 로그인 때 기본값)
    ADD COLUMN failed_login_count  INT          NOT NULL DEFAULT 0,
    ADD COLUMN last_login_at       DATETIME(6)  NULL,
    ADD COLUMN password_changed_at DATETIME(6)  NULL,
    ADD COLUMN must_change_password BOOLEAN     NOT NULL DEFAULT FALSE,   -- 관리자가 임시 비밀번호를 줬으면 TRUE → 다음 로그인 때 변경 화면으로 강제
    ADD COLUMN email_verified_at   DATETIME(6)  NULL,      -- 이메일 소유 확인 시각 (아이디 = 이메일이라 비밀번호 재설정 메일이 닿는지 보장)
    ADD COLUMN email_verified_via  VARCHAR(10)  NULL,      -- LINK(인증 메일 클릭) | ADMIN(메일이 막힌 환경에서 관리자가 대면 확인)
    ADD COLUMN lock_version        INT          NOT NULL DEFAULT 0,
    ADD COLUMN updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6);

-- 기존 사용자는 가입 승인 대기로 시작 (관리자가 현장 권한을 주면 ACTIVE). 새 컬럼 기본값이 이미 PENDING

ALTER TABLE users
    ADD KEY idx_users_role (role, status),
    ADD CONSTRAINT ck_users_role   CHECK (role IN ('ADMIN', 'MEMBER')),
    ADD CONSTRAINT ck_users_status CHECK (status IN ('PENDING', 'ACTIVE', 'INACTIVE', 'LOCKED')),
    ADD CONSTRAINT ck_users_phone  CHECK (phone IS NULL OR phone REGEXP '^0[0-9]{8,10}$'),
    ADD CONSTRAINT ck_users_failed CHECK (failed_login_count >= 0),
    ADD CONSTRAINT ck_users_verify_pair CHECK ((email_verified_at IS NULL) = (email_verified_via IS NULL)),
    ADD CONSTRAINT ck_users_verify_via  CHECK (email_verified_via IS NULL OR email_verified_via IN ('LINK', 'ADMIN')),
    -- 이메일 확인 안 된 계정은 사용 불가 (승인 대기로 남음)
    ADD CONSTRAINT ck_users_active_verified CHECK (status <> 'ACTIVE' OR email_verified_at IS NOT NULL),
    ADD CONSTRAINT fk_users_last_site FOREIGN KEY (last_site_id) REFERENCES sites (id);

-- 현장별 권한. MEMBER의 드롭다운 = 여기 ACTIVE인 현장들. ADMIN은 이 표와 관계없이 모든 현장
CREATE TABLE site_members (
    user_id         BIGINT      NOT NULL,
    site_id         BIGINT      NOT NULL,
    role            VARCHAR(20) NOT NULL,
    status          VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    granted_by      BIGINT      NOT NULL,
    lock_version    INT         NOT NULL DEFAULT 0,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, site_id),
    KEY idx_sm_site_role (site_id, role, status),           -- 이 현장 승인자 찾기 (알림 수신자)
    CONSTRAINT fk_sm_user    FOREIGN KEY (user_id)    REFERENCES users (id),
    CONSTRAINT fk_sm_site    FOREIGN KEY (site_id)    REFERENCES sites (id),
    CONSTRAINT fk_sm_granter FOREIGN KEY (granted_by) REFERENCES users (id),
    CONSTRAINT ck_sm_role   CHECK (role IN ('WRITER', 'APPROVER', 'SITE_ADMIN')),
    CONSTRAINT ck_sm_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 로그인 세션 (리프레시 토큰). 원문은 저장하지 않고 SHA-256 해시만. 로그아웃·잠금 시 즉시 무효화
CREATE TABLE refresh_tokens (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    token_hash    CHAR(64)     NOT NULL,
    issued_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at    DATETIME(6)  NOT NULL,
    revoked_at    DATETIME(6)  NULL,
    revoke_reason VARCHAR(20)  NULL,
    replaced_by   BIGINT       NULL,                        -- 재발급(회전) 시 새 토큰. 이미 회전된 토큰이 다시 오면 탈취로 보고 전부 폐기
    user_agent    VARCHAR(200) NULL,
    ip            VARCHAR(45)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rt_hash (token_hash),
    KEY idx_rt_user (user_id, revoked_at, expires_at),
    CONSTRAINT fk_rt_user     FOREIGN KEY (user_id)     REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_rt_replaced FOREIGN KEY (replaced_by) REFERENCES refresh_tokens (id),
    CONSTRAINT ck_rt_hash    CHECK (token_hash REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_rt_expiry  CHECK (expires_at > issued_at),
    CONSTRAINT ck_rt_revoke  CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL)),
    CONSTRAINT ck_rt_reason  CHECK (revoke_reason IS NULL OR revoke_reason IN ('LOGOUT', 'ROTATED', 'LOCKED', 'ADMIN', 'REUSE_DETECTED')),
    CONSTRAINT ck_rt_rotated CHECK ((revoke_reason = 'ROTATED') = (replaced_by IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 소속 (부서·팀·외부 업체). 작업과 작업자가 어느 소속인지. 문자열로 두면 "정비팀"/"정비 팀"이 갈려 소속별 통계가 깨진다
CREATE TABLE teams (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    team_type   VARCHAR(10)  NOT NULL DEFAULT 'INTERNAL',   -- INTERNAL(사내 부서·팀) | EXTERNAL(외부 업체)
    business_no CHAR(10)     NULL,                          -- 외부 업체면 사업자등록번호
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_teams_name (name),
    UNIQUE KEY uk_teams_bizno (business_no),
    CONSTRAINT ck_teams_type  CHECK (team_type IN ('INTERNAL', 'EXTERNAL')),
    CONSTRAINT ck_teams_bizno CHECK (business_no IS NULL OR (team_type = 'EXTERNAL' AND business_no REGEXP '^[0-9]{10}$')),
    CONSTRAINT ck_teams_name  CHECK (CHAR_LENGTH(TRIM(name)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 작업자 명단 (회사 단위). 로그인 계정이 없는 인원(외부 업체·일용직)도 여기에. 계정이 있으면 user_id로 연결
-- 어느 현장에서 일하는지는 worker_sites. 작업자가 현장을 옮겨도 과거 기록(배치·참석·서명)은 그대로 남는다
CREATE TABLE workers (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NULL,
    team_id         BIGINT       NULL,
    name            VARCHAR(50)  NOT NULL,
    phone           VARCHAR(20)  NULL,
    job_type        VARCHAR(50)  NULL,                      -- 예: 용접, 배관, 전기
    employment_type VARCHAR(20)  NOT NULL DEFAULT 'DIRECT',
    status          VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    lock_version    INT          NOT NULL DEFAULT 0,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_workers_user (user_id),
    KEY idx_workers_team (team_id, status, name),
    CONSTRAINT fk_workers_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_workers_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT ck_workers_phone  CHECK (phone IS NULL OR phone REGEXP '^0[0-9]{8,10}$'),
    CONSTRAINT ck_workers_emp    CHECK (employment_type IN ('DIRECT', 'CONTRACTOR', 'DAILY')),
    CONSTRAINT ck_workers_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 작업자 현장 배치. 한 현장에 한 행 (다시 오면 active만 바꿈). 작업 배치·TBM 참석·서명은 이 (작업자, 현장) 쌍을 참조해서
-- "그 현장에 배치된 적 있는 작업자"만 들어간다. 현장을 떠나도 행은 남으므로 과거 기록이 깨지지 않는다
CREATE TABLE worker_sites (
    worker_id     BIGINT      NOT NULL,
    site_id       BIGINT      NOT NULL,
    active        BOOLEAN     NOT NULL DEFAULT TRUE,        -- 지금 이 현장에서 일하는가 (작업 배치 화면 목록)
    assigned_from DATE        NOT NULL,
    assigned_to   DATE        NULL,                         -- 떠난 날
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (worker_id, site_id),
    KEY idx_ws_site (site_id, active),
    CONSTRAINT fk_ws_worker FOREIGN KEY (worker_id) REFERENCES workers (id),
    CONSTRAINT fk_ws_site   FOREIGN KEY (site_id)   REFERENCES sites (id),
    CONSTRAINT ck_ws_period CHECK (assigned_to IS NULL OR assigned_to >= assigned_from),
    CONSTRAINT ck_ws_active CHECK (NOT active OR assigned_to IS NULL)          -- 떠난 날이 있으면 활성 아님
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 현장 취급 물질 (MSDS 비치 대상 목록). 구역별로 무엇이 있는지 알아야 화기작업 시 인화성 물질 경고를 낼 수 있다
-- 물질 상세(GHS·응급조치)는 지식 DB msds_cache에 있다 (CAS 번호로 느슨하게 연결)
CREATE TABLE site_chemicals (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    site_id         BIGINT        NOT NULL,
    zone_id         BIGINT        NULL,                     -- NULL = 현장 전체(창고 등 구역 미지정)
    zone_key        BIGINT        GENERATED ALWAYS AS (COALESCE(zone_id, 0)) STORED,
    cas_no          VARCHAR(20)   NULL,                     -- 혼합물·영업비밀 등으로 없을 수 있음
    name            VARCHAR(200)  NOT NULL,
    max_quantity    DECIMAL(14,3) NULL,
    unit            VARCHAR(10)   NULL,                     -- KG | L | TON | M3
    msds_revised_on DATE          NULL,                     -- 비치된 MSDS 개정일
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_name (site_id, zone_key, name),
    KEY idx_sc_cas (cas_no),
    KEY idx_sc_zone (zone_id),
    CONSTRAINT fk_sc_site FOREIGN KEY (site_id)          REFERENCES sites (id),
    CONSTRAINT fk_sc_zone FOREIGN KEY (zone_id, site_id) REFERENCES work_zones (id, site_id),
    CONSTRAINT ck_sc_cas  CHECK (cas_no IS NULL OR cas_no REGEXP '^[0-9]{2,7}-[0-9]{2}-[0-9]$'),
    CONSTRAINT ck_sc_qty  CHECK ((max_quantity IS NULL) = (unit IS NULL) AND (max_quantity IS NULL OR max_quantity >= 0)),
    CONSTRAINT ck_sc_unit CHECK (unit IS NULL OR unit IN ('KG', 'L', 'TON', 'M3'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 교육·자격·건강진단. 규칙 엔진이 "밀폐공간 작업자 특별교육 이수" 같은 조건을 판정할 때 읽는다
CREATE TABLE worker_qualifications (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    worker_id     BIGINT       NOT NULL,
    qual_type     VARCHAR(20)  NOT NULL,                    -- TRAINING | LICENSE | HEALTH_CHECK
    qual_code     VARCHAR(50)  NOT NULL,                    -- 예: CONFINED_SPACE_SPECIAL, WELDING_LICENSE
    qual_name     VARCHAR(100) NOT NULL,
    issued_on     DATE         NOT NULL,
    expires_on    DATE         NULL,                        -- 유효기간 없는 자격은 NULL
    issuer        VARCHAR(100) NULL,
    cert_no       VARCHAR(50)  NULL,
    proof_attachment_id BIGINT NULL,                        -- 수료증 사진 (FK는 V4)
    created_by    BIGINT       NOT NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_wq_worker (worker_id, qual_code, expires_on),
    CONSTRAINT fk_wq_worker  FOREIGN KEY (worker_id)  REFERENCES workers (id),
    CONSTRAINT fk_wq_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_wq_type   CHECK (qual_type IN ('TRAINING', 'LICENSE', 'HEALTH_CHECK')),
    CONSTRAINT ck_wq_code   CHECK (qual_code REGEXP '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_wq_period CHECK (expires_on IS NULL OR expires_on >= issued_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────
-- 계정 토큰: 이메일 인증, 이메일 변경 확인, 비밀번호 재설정 (모두 메일 링크로 전달)
--  * 가입 → EMAIL_VERIFY 메일 → 링크 클릭 → email_verified_at 기록 → 관리자 승인 → ACTIVE
--  * 이메일 변경 → 새 주소로 EMAIL_CHANGE 메일 → 클릭해야 users.email이 바뀜 (그 전까지 옛 주소로 로그인)
--  * 비밀번호 찾기 → PASSWORD_RESET 메일 (30분) → 새 비밀번호
--  * 메일이 막힌 환경: 관리자가 대면 확인 후 인증 처리(email_verified_via = 'ADMIN'), 비밀번호는 관리자 초기화(임시 비밀번호)
--  공통(비밀번호 변경 시): refresh_tokens 전부 폐기, failed_login_count = 0, LOCKED면 ACTIVE로
-- ─────────────────────────────────────────────────────────────
CREATE TABLE account_tokens (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    purpose      VARCHAR(20)  NOT NULL,
    token_hash   CHAR(64)     NOT NULL,                     -- 메일 링크 토큰의 SHA-256 (원문 저장 금지)
    target_email VARCHAR(255) NULL,                         -- EMAIL_CHANGE일 때 바꿀 새 주소
    requested_ip VARCHAR(45)  NULL,                         -- 같은 IP 반복 요청 제한용 (앱: 1시간 5회)
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at   DATETIME(6)  NOT NULL,
    used_at      DATETIME(6)  NULL,
    revoked_at   DATETIME(6)  NULL,                         -- 새 요청이 오면 같은 목적의 이전 토큰은 폐기
    -- 사용자·목적당 쓸 수 있는 토큰은 하나만 (새로 요청하면 같은 트랜잭션에서 이전 것을 revoked 처리해야 들어감)
    active_key   VARCHAR(40)  GENERATED ALWAYS AS (IF(used_at IS NULL AND revoked_at IS NULL, CONCAT(user_id, ':', purpose), NULL)) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_at_hash (token_hash),
    UNIQUE KEY uk_at_active (active_key),
    KEY idx_at_user (user_id, purpose, created_at),
    KEY idx_at_ip (requested_ip, created_at),
    -- 생성 컬럼(active_key)의 재료 컬럼이라 ON DELETE CASCADE를 걸 수 없다 (MySQL 제약). 사용자는 지우지 않으므로 문제없음
    CONSTRAINT fk_at_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_at_purpose CHECK (purpose IN ('EMAIL_VERIFY', 'EMAIL_CHANGE', 'PASSWORD_RESET')),
    CONSTRAINT ck_at_hash    CHECK (token_hash REGEXP '^[0-9a-f]{64}$'),
    -- 유효시간: 비밀번호 재설정은 최대 1시간, 이메일 인증·변경은 최대 72시간
    CONSTRAINT ck_at_expiry  CHECK (expires_at > created_at AND expires_at <= created_at +
                                    INTERVAL (IF(purpose = 'PASSWORD_RESET', 1, 72)) HOUR),
    CONSTRAINT ck_at_target  CHECK ((purpose = 'EMAIL_CHANGE') = (target_email IS NOT NULL)),
    CONSTRAINT ck_at_email   CHECK (target_email IS NULL OR target_email REGEXP '^[^@[:space:]]+@[^@[:space:]]+\\.[^@[:space:]]+$'),
    CONSTRAINT ck_at_once    CHECK (used_at IS NULL OR revoked_at IS NULL),
    CONSTRAINT ck_at_used    CHECK (used_at IS NULL OR used_at >= created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 최근 비밀번호 재사용 금지 (직전 3개). 해시만 저장
CREATE TABLE password_history (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_ph_user (user_id, created_at),
    CONSTRAINT fk_ph_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
