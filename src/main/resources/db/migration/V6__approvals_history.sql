-- V6: 승인 흐름, 작성·승인 이력(감사 대응), 일일 안전일지, 알림, 서명, 양식·생성 문서, 열람 기록, 임시저장, 비동기 작업
-- 담당: C 작성, 업무 소유 D

-- 결재 요청 1건 = approvals 1행, 결재선의 단계들 = approval_steps N행.
-- 대형 현장은 "작업책임자 → 부서장 → 안전관리자"처럼 여러 단계, 50인 미만은 1단계(자기 확인 포함).
-- 결재선은 approval_policies(현장 × 문서 종류)로 정하고, 요청할 때 단계를 복사해 만든다(정책이 바뀌어도 진행 중인 결재는 그대로).
CREATE TABLE approvals (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    site_id      BIGINT       NOT NULL,
    target_type  VARCHAR(20)  NOT NULL,
    target_id    BIGINT       NOT NULL,
    requested_by BIGINT       NOT NULL,
    approver_id  BIGINT       NULL,                         -- 최종 결정한 사람 (마지막 단계 승인자 또는 반려한 사람)
    current_step INT          NOT NULL DEFAULT 1,           -- 지금 결재할 단계
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    comment      VARCHAR(500) NULL,
    due_at       DATETIME(6)  NULL,
    requested_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    decided_at   DATETIME(6)  NULL,
    -- 같은 기록에 대기 중인 승인 요청은 하나만 (MySQL엔 부분 유니크가 없어 생성 컬럼으로 구현)
    pending_key  VARCHAR(40)  GENERATED ALWAYS AS (IF(status = 'PENDING', CONCAT(target_type, ':', target_id), NULL)) STORED,
    lock_version INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_appr_pending (pending_key),
    KEY idx_appr_target (target_type, target_id),
    KEY idx_appr_inbox (site_id, status, requested_at),
    CONSTRAINT fk_appr_site      FOREIGN KEY (site_id)      REFERENCES sites (id),
    CONSTRAINT fk_appr_requester FOREIGN KEY (requested_by) REFERENCES users (id),
    CONSTRAINT fk_appr_approver  FOREIGN KEY (approver_id)  REFERENCES users (id),
    CONSTRAINT ck_appr_target  CHECK (target_type IN ('WORK', 'RISK', 'PERMIT', 'PERMIT_EVENT', 'INSPECTION', 'TBM', 'DAILY_LOG', 'CORRECTIVE_ACTION')),
    CONSTRAINT ck_appr_step    CHECK (current_step >= 1),
    CONSTRAINT ck_appr_status  CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELED')),
    -- 결정되면 결정 시각 필수, 대기 중이면 결정 시각 없음
    CONSTRAINT ck_appr_decided CHECK ((status = 'PENDING') = (decided_at IS NULL)),
    -- 승인·반려는 결정한 사람이 있어야 함
    CONSTRAINT ck_appr_who     CHECK (status NOT IN ('APPROVED', 'REJECTED') OR approver_id IS NOT NULL),
    -- 반려는 사유 필수
    CONSTRAINT ck_appr_reason  CHECK (status <> 'REJECTED' OR comment IS NOT NULL),
    CONSTRAINT ck_appr_order   CHECK (decided_at IS NULL OR decided_at >= requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- 작성자 = 승인자 금지 여부는 현장 모드에 따라 다르다 (안내 모드는 자기 확인 허용) → 앱에서 판단

CREATE TABLE approval_steps (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    approval_id   BIGINT       NOT NULL,
    step_no       INT          NOT NULL,
    label         VARCHAR(50)  NOT NULL,                    -- 예: 부서장 확인, 안전관리자 승인
    required_role VARCHAR(20)  NOT NULL,                    -- 이 단계를 결재할 수 있는 현장 역할 (이상)
    assignee_id   BIGINT       NULL,                        -- 특정인 지정 시
    status        VARCHAR(10)  NOT NULL DEFAULT 'WAITING',  -- WAITING(앞 단계 대기) → PENDING(내 차례) → APPROVED/REJECTED, 앞에서 반려되면 SKIPPED
    decided_by    BIGINT       NULL,
    decided_at    DATETIME(6)  NULL,
    comment       VARCHAR(500) NULL,
    -- 한 결재에서 "내 차례" 단계는 하나만
    pending_key   BIGINT       GENERATED ALWAYS AS (IF(status = 'PENDING', approval_id, NULL)) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_aps_step (approval_id, step_no),
    UNIQUE KEY uk_aps_pending (pending_key),
    KEY idx_aps_inbox (assignee_id, status),                 -- 지정 결재함
    CONSTRAINT fk_aps_appr     FOREIGN KEY (approval_id) REFERENCES approvals (id),
    CONSTRAINT fk_aps_assignee FOREIGN KEY (assignee_id) REFERENCES users (id),
    CONSTRAINT fk_aps_decider  FOREIGN KEY (decided_by)  REFERENCES users (id),
    CONSTRAINT ck_aps_stepno  CHECK (step_no BETWEEN 1 AND 10),
    CONSTRAINT ck_aps_role    CHECK (required_role IN ('APPROVER', 'SITE_ADMIN', 'ADMIN')),
    CONSTRAINT ck_aps_status  CHECK (status IN ('WAITING', 'PENDING', 'APPROVED', 'REJECTED', 'SKIPPED')),
    CONSTRAINT ck_aps_decided CHECK ((status IN ('APPROVED', 'REJECTED')) = (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT ck_aps_reason  CHECK (status <> 'REJECTED' OR comment IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 결재선 정책: 현장 × 문서 종류마다 단계 목록. 행이 없으면 기본 1단계(APPROVER)
CREATE TABLE approval_policies (
    site_id       BIGINT      NOT NULL,
    target_type   VARCHAR(20) NOT NULL,
    step_no       INT         NOT NULL,
    label         VARCHAR(50) NOT NULL,
    required_role VARCHAR(20) NOT NULL,
    updated_by    BIGINT      NOT NULL,
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (site_id, target_type, step_no),
    CONSTRAINT fk_ap_site FOREIGN KEY (site_id)    REFERENCES sites (id),
    CONSTRAINT fk_ap_user FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_ap_target CHECK (target_type IN ('WORK', 'RISK', 'PERMIT', 'PERMIT_EVENT', 'INSPECTION', 'TBM', 'DAILY_LOG', 'CORRECTIVE_ACTION')),
    CONSTRAINT ck_ap_stepno CHECK (step_no BETWEEN 1 AND 10),
    CONSTRAINT ck_ap_role   CHECK (required_role IN ('APPROVER', 'SITE_ADMIN', 'ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 이력: 추가만 가능 (V9 트리거로 수정·삭제 차단)
CREATE TABLE record_history (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    site_id     BIGINT      NULL,                           -- 현장에 속하지 않는 작업(현장 생성, 관리자 계정 변경 등)은 NULL
    target_type VARCHAR(20) NOT NULL,
    target_id   BIGINT      NOT NULL,
    action      VARCHAR(20) NOT NULL,
    actor_type  VARCHAR(10) NOT NULL DEFAULT 'USER',
    actor_id    BIGINT      NULL,
    before_json JSON        NULL,
    after_json  JSON        NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_hist_target (target_type, target_id, created_at),
    KEY idx_hist_site_time (site_id, created_at),
    KEY idx_hist_actor (actor_type, actor_id, created_at),
    CONSTRAINT ck_hist_target CHECK (target_type IN ('WORK', 'RISK', 'PERMIT', 'INSPECTION', 'TBM', 'NEAR_MISS',
                                                     'DAILY_LOG', 'APPROVAL', 'USER', 'SITE', 'ZONE', 'EQUIPMENT', 'RULE',
                                                     'SITE_MEMBER', 'WORKER', 'QUALIFICATION',
                                                     'CORRECTIVE_ACTION', 'WARNING', 'FORM_TEMPLATE', 'DOCUMENT',
                                                     'PERMIT_EVENT', 'APPROVAL_POLICY', 'TEAM', 'CHEMICAL')),
    CONSTRAINT ck_hist_action CHECK (action IN ('CREATE', 'UPDATE', 'SUBMIT', 'APPROVE', 'REJECT', 'BLOCK', 'UNBLOCK',
                                                'AI_DRAFT', 'CLOSE', 'CANCEL', 'ROLE_CHANGE', 'STATUS_CHANGE',
                                                'GRANT', 'REVOKE', 'ACKNOWLEDGE', 'VERIFY', 'SIGN', 'EXPORT',
                                                'PASSWORD_RESET', 'PASSWORD_CHANGE', 'UNLOCK', 'EMAIL_VERIFY', 'EMAIL_CHANGE')),
    CONSTRAINT ck_hist_actor  CHECK (actor_type IN ('USER', 'AGENT', 'SYSTEM')),
    CONSTRAINT ck_hist_actor_id CHECK (actor_type = 'SYSTEM' OR actor_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE daily_safety_logs (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    site_id      BIGINT      NOT NULL,
    log_date     DATE        NOT NULL,
    summary      TEXT        NULL,
    stats        JSON        NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    writer_id    BIGINT      NULL,
    agent_run_id BIGINT      NULL,
    lock_version INT         NOT NULL DEFAULT 0,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dsl_site_date (site_id, log_date),
    CONSTRAINT fk_dsl_site   FOREIGN KEY (site_id)   REFERENCES sites (id),
    CONSTRAINT fk_dsl_writer FOREIGN KEY (writer_id) REFERENCES users (id),
    CONSTRAINT ck_dsl_status CHECK (status IN ('DRAFT', 'PENDING', 'APPROVED')),
    CONSTRAINT ck_dsl_author CHECK (writer_id IS NOT NULL OR agent_run_id IS NOT NULL),  -- 사람이든 에이전트든 작성 주체 필수
    CONSTRAINT ck_dsl_submit CHECK (status = 'DRAFT' OR summary IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────
-- 알림: "알림 내용 1건" + "받는 사람별 상태 N건"으로 나눈다.
--  * 승인 결과 1건이 작성자·승인자·관리자 여러 명에게 가지만, 읽음·끄기는 사람마다 다르다.
--  * 화면은 끄기(dismissed_at) 전까지 계속 보여주고, 배지 숫자는 안 읽은 것(read_at IS NULL)만 센다.
--  * 비동기 전달: 업무 트랜잭션 안에서 두 테이블에 INSERT → 커밋 후(AFTER_COMMIT) SSE로 푸시.
--    푸시가 실패해도 행이 이미 저장돼 있으므로 재접속·폴링 때 빠짐없이 받는다.
-- ─────────────────────────────────────────────────────────────

CREATE TABLE notifications (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    site_id     BIGINT       NULL,                          -- 시스템 공지는 NULL
    type        VARCHAR(30)  NOT NULL,
    severity    VARCHAR(10)  NOT NULL DEFAULT 'INFO',       -- INFO | WARN | CRITICAL (화면 색, CRITICAL은 끄기 전 확인창)
    title       VARCHAR(100) NOT NULL,                      -- 예: 작업허가 승인
    message     VARCHAR(500) NOT NULL,                      -- 예: R-201 화기작업 허가가 승인되었습니다.
    target_type VARCHAR(20)  NULL,                          -- 누르면 이동할 기록
    target_id   BIGINT       NULL,
    actor_type  VARCHAR(10)  NOT NULL DEFAULT 'SYSTEM',     -- 알림을 일으킨 주체
    actor_id    BIGINT       NULL,
    dedupe_key  VARCHAR(120) NULL,                          -- 같은 알림 중복 방지. 예: DEADLINE:TBM:1001:2026-10-26
    expires_at  DATETIME(6)  NULL,                          -- 지나면 화면에서 자동으로 안 보임 (마감 알림 등)
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_noti_dedupe (dedupe_key),
    KEY idx_noti_target (target_type, target_id),
    KEY idx_noti_site_time (site_id, created_at),
    CONSTRAINT fk_noti_site FOREIGN KEY (site_id) REFERENCES sites (id),
    CONSTRAINT ck_noti_type CHECK (type IN ('APPROVAL_REQUEST', 'APPROVAL_RESULT', 'BLOCKED', 'NEAR_MISS_WARNING',
                                            'DEADLINE', 'ACTION_DUE', 'AGENT_DONE', 'DOCUMENT_READY', 'JOB_FAILED', 'ACCOUNT', 'SYSTEM')),
    CONSTRAINT ck_noti_severity CHECK (severity IN ('INFO', 'WARN', 'CRITICAL')),
    CONSTRAINT ck_noti_target   CHECK ((target_type IS NULL) = (target_id IS NULL)),
    CONSTRAINT ck_noti_target_t CHECK (target_type IS NULL OR target_type IN ('WORK', 'RISK', 'PERMIT', 'INSPECTION', 'TBM',
                                                                              'NEAR_MISS', 'DAILY_LOG', 'APPROVAL', 'AGENT_RUN', 'USER',
                                                                              'CORRECTIVE_ACTION', 'DOCUMENT', 'JOB')),
    CONSTRAINT ck_noti_actor    CHECK (actor_type IN ('USER', 'AGENT', 'SYSTEM')),
    CONSTRAINT ck_noti_actor_id CHECK (actor_type = 'SYSTEM' OR actor_id IS NOT NULL),
    CONSTRAINT ck_noti_expires  CHECK (expires_at IS NULL OR expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 사용자별 수신함. 화면 목록·배지·모두 읽음·끄기는 전부 이 테이블에서
CREATE TABLE notification_recipients (
    id              BIGINT      NOT NULL AUTO_INCREMENT,    -- SSE 이벤트 id로도 쓴다 (재접속 시 이 id 이후만 받기)
    notification_id BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    delivered_at    DATETIME(6) NULL,                       -- 실시간 푸시 성공 시각 (NULL이면 재접속 때 보냄)
    read_at         DATETIME(6) NULL,                       -- 읽음 (배지에서 빠짐, 목록에는 남음)
    dismissed_at    DATETIME(6) NULL,                       -- 끄기 (목록에서 사라짐)
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_nr_user (notification_id, user_id),       -- 같은 알림을 한 사람에게 두 번 넣지 않음
    KEY idx_nr_inbox (user_id, dismissed_at, id),           -- 화면 목록: 끄지 않은 것 최신순
    KEY idx_nr_unread (user_id, read_at, dismissed_at),     -- 배지 숫자
    KEY idx_nr_undelivered (user_id, delivered_at, id),     -- 재접속 시 못 받은 것
    KEY idx_nr_cleanup (dismissed_at),                      -- 정리 배치: X 누른 지 30일 지난 행 삭제
    CONSTRAINT fk_nr_noti FOREIGN KEY (notification_id) REFERENCES notifications (id) ON DELETE CASCADE,
    CONSTRAINT fk_nr_user FOREIGN KEY (user_id)         REFERENCES users (id),
    CONSTRAINT ck_nr_dismiss CHECK (dismissed_at IS NULL OR read_at IS NOT NULL),   -- 끄면 읽음 처리도 같이
    CONSTRAINT ck_nr_order   CHECK (read_at IS NULL OR read_at >= created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 사용자별 알림 설정. 행이 없으면 기본값(웹 켜짐, 문자 꺼짐)
CREATE TABLE notification_preferences (
    user_id     BIGINT      NOT NULL,
    type        VARCHAR(30) NOT NULL,
    web_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    sms_enabled BOOLEAN     NOT NULL DEFAULT FALSE,         -- 문자 발송은 이후 확장 (users.phone 사용)
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, type),
    CONSTRAINT fk_np_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_np_type CHECK (type IN ('APPROVAL_REQUEST', 'APPROVAL_RESULT', 'BLOCKED', 'NEAR_MISS_WARNING',
                                          'DEADLINE', 'ACTION_DUE', 'AGENT_DONE', 'DOCUMENT_READY', 'JOB_FAILED', 'ACCOUNT', 'SYSTEM')),
    -- 안전 관련 필수 알림은 끌 수 없음
    CONSTRAINT ck_np_mandatory CHECK (type NOT IN ('BLOCKED', 'NEAR_MISS_WARNING', 'APPROVAL_REQUEST') OR web_enabled = TRUE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────
-- 서명: 서명 순간의 문서 내용 해시를 같이 저장 → 서명 후 내용이 바뀌면 해시가 안 맞아 위조가 드러난다 (V9 트리거로 수정·삭제 금지)
-- ─────────────────────────────────────────────────────────────
CREATE TABLE signatures (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    site_id             BIGINT      NOT NULL,
    signer_type         VARCHAR(10) NOT NULL,               -- USER | WORKER (계정 없는 작업자)
    user_id             BIGINT      NULL,
    worker_id           BIGINT      NULL,
    signer_key          VARCHAR(30) GENERATED ALWAYS AS (CONCAT(signer_type, ':', COALESCE(user_id, worker_id))) STORED,
    target_type         VARCHAR(20) NOT NULL,
    target_id           BIGINT      NOT NULL,
    purpose             VARCHAR(20) NOT NULL,               -- 무슨 자격으로 서명했나
    image_attachment_id BIGINT      NULL,                   -- 서명 이미지 (패드 서명)
    content_sha256      CHAR(64)    NOT NULL,               -- 서명 시점 문서 내용 해시
    signed_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ip                  VARCHAR(45) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sig_once (target_type, target_id, purpose, signer_key),   -- 같은 사람이 같은 자격으로 두 번 서명 금지
    KEY idx_sig_target (target_type, target_id),
    CONSTRAINT fk_sig_site   FOREIGN KEY (site_id)             REFERENCES sites (id),
    CONSTRAINT fk_sig_user   FOREIGN KEY (user_id)             REFERENCES users (id),
    CONSTRAINT fk_sig_worker FOREIGN KEY (worker_id, site_id)  REFERENCES worker_sites (worker_id, site_id),
    CONSTRAINT fk_sig_image  FOREIGN KEY (image_attachment_id) REFERENCES attachments (id),
    CONSTRAINT ck_sig_type    CHECK (signer_type IN ('USER', 'WORKER')),
    CONSTRAINT ck_sig_signer  CHECK ((signer_type = 'USER' AND user_id IS NOT NULL AND worker_id IS NULL)
                                  OR (signer_type = 'WORKER' AND worker_id IS NOT NULL AND user_id IS NULL)),
    CONSTRAINT ck_sig_target  CHECK (target_type IN ('PERMIT', 'TBM', 'INSPECTION', 'RISK', 'DOCUMENT', 'CORRECTIVE_ACTION')),
    CONSTRAINT ck_sig_purpose CHECK (purpose IN ('WRITER', 'APPROVER', 'ATTENDEE', 'FIRE_WATCHER', 'ATTENDANT', 'SUPERVISOR', 'VERIFIER')),
    CONSTRAINT ck_sig_hash    CHECK (content_sha256 REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 양식 정의: 양식 종류 × 판(version) × 적용 범위(전체 / 현장). 범위마다 켜진 판은 하나
-- 업무 테이블의 form_schema_version 값이 여기 version과 맞아야 한다
CREATE TABLE form_templates (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    form_type           VARCHAR(30)  NOT NULL,
    version             VARCHAR(20)  NOT NULL,
    site_id             BIGINT       NULL,                  -- NULL = 회사 전체 공통, 값 = 그 현장 전용 변형
    scope_key           VARCHAR(30)  GENERATED ALWAYS AS (IF(site_id IS NULL, 'ALL', CONCAT('S', site_id))) STORED,
    json_schema         JSON         NOT NULL,              -- 입력 검증용 JSON 스키마
    print_template_path VARCHAR(300) NOT NULL,              -- 인쇄 템플릿 파일 경로 (저장소 기준)
    status              VARCHAR(10)  NOT NULL DEFAULT 'DRAFT',
    active_key          VARCHAR(70)  GENERATED ALWAYS AS (IF(status = 'ACTIVE', CONCAT(form_type, ':', scope_key), NULL)) STORED,
    created_by          BIGINT       NOT NULL,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ft_version (form_type, version, scope_key),
    UNIQUE KEY uk_ft_active (active_key),                   -- 같은 범위에 켜진 판 2개 금지
    CONSTRAINT fk_ft_site    FOREIGN KEY (site_id)    REFERENCES sites (id),
    CONSTRAINT fk_ft_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_ft_type    CHECK (form_type IN ('RISK_ASSESSMENT', 'HOT_WORK_PERMIT', 'CONFINED_SPACE_PERMIT', 'HEIGHT_WORK_PERMIT',
                                                  'INSPECTION', 'TBM', 'DAILY_LOG', 'AUDIT_REPORT')),
    CONSTRAINT ck_ft_status  CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_ft_version CHECK (version REGEXP '^v[0-9]+(\\.[0-9]+)?$'),
    CONSTRAINT ck_ft_schema  CHECK (JSON_TYPE(json_schema) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 생성 문서: 인쇄 PDF·감사 보고서. 출력 시점 데이터를 통째로 남겨 "그날 무엇을 인쇄했나"를 재현 (V9 트리거로 수정·삭제 금지)
CREATE TABLE generated_documents (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    site_id          BIGINT       NOT NULL,
    form_type        VARCHAR(30)  NOT NULL,
    template_id      BIGINT       NOT NULL,
    target_type      VARCHAR(20)  NOT NULL,
    target_id        BIGINT       NOT NULL,
    source_version   INT          NOT NULL,                 -- 출력한 기록의 lock_version (저장된 판만 출력. 임시저장 내용은 출력 불가)
    seq              INT          NOT NULL,                 -- 같은 기록·양식의 n번째 출력
    file_path        VARCHAR(500) NOT NULL,
    file_sha256      CHAR(64)     NOT NULL,
    page_count       INT          NULL,
    data_snapshot    JSON         NOT NULL,                 -- 출력 당시 데이터 전체
    created_by_type  VARCHAR(10)  NOT NULL,
    created_by_id    BIGINT       NOT NULL,
    agent_run_id     BIGINT       NULL,                     -- FK는 V8
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_gd_seq (target_type, target_id, form_type, seq),
    UNIQUE KEY uk_gd_path (file_path),
    KEY idx_gd_site_time (site_id, created_at),
    CONSTRAINT fk_gd_site     FOREIGN KEY (site_id)     REFERENCES sites (id),
    CONSTRAINT fk_gd_template FOREIGN KEY (template_id) REFERENCES form_templates (id),
    CONSTRAINT ck_gd_type     CHECK (form_type IN ('RISK_ASSESSMENT', 'HOT_WORK_PERMIT', 'CONFINED_SPACE_PERMIT', 'HEIGHT_WORK_PERMIT',
                                                   'INSPECTION', 'TBM', 'DAILY_LOG', 'AUDIT_REPORT')),
    CONSTRAINT ck_gd_target   CHECK (target_type IN ('RISK', 'PERMIT', 'INSPECTION', 'TBM', 'DAILY_LOG', 'SITE')),  -- 감사 보고서는 SITE
    CONSTRAINT ck_gd_seq      CHECK (seq >= 1),
    CONSTRAINT ck_gd_version  CHECK (source_version >= 0),
    CONSTRAINT ck_gd_hash     CHECK (file_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_gd_pages    CHECK (page_count IS NULL OR page_count > 0),
    CONSTRAINT ck_gd_by       CHECK (created_by_type IN ('USER', 'AGENT')),
    CONSTRAINT ck_gd_agent    CHECK (created_by_type <> 'AGENT' OR agent_run_id IS NOT NULL),
    CONSTRAINT ck_gd_snapshot CHECK (JSON_TYPE(data_snapshot) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 열람 기록: 개인정보(전화번호)·감사 자료·내보내기를 누가 봤나 (V9 트리거로 수정·삭제 금지)
CREATE TABLE record_access_logs (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    site_id       BIGINT       NULL,
    resource_type VARCHAR(20)  NOT NULL,
    resource_id   BIGINT       NULL,
    action        VARCHAR(10)  NOT NULL,
    detail        JSON         NULL,                        -- 질의 조건, 내보낸 범위 등
    ip            VARCHAR(45)  NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_ral_user (user_id, created_at),
    KEY idx_ral_site (site_id, created_at),
    KEY idx_ral_resource (resource_type, resource_id),
    CONSTRAINT fk_ral_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_ral_site FOREIGN KEY (site_id) REFERENCES sites (id),
    CONSTRAINT ck_ral_type   CHECK (resource_type IN ('USER_PII', 'WORKER_PII', 'AUDIT_REPORT', 'RECORD_QUERY', 'DOCUMENT')),
    CONSTRAINT ck_ral_action CHECK (action IN ('VIEW', 'DOWNLOAD', 'EXPORT', 'QUERY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────
-- 임시저장: 화면에서 고치는 중인 내용. 정식 기록과 분리한다.
--  * 승인자·다른 사람에게 반쯤 고친 내용이 보이지 않는다
--  * 키 입력마다 record_history·lock_version이 늘어나지 않는다 (정식 저장 때 1번만)
--  * 브라우저가 꺼져도 이어서 쓸 수 있다
-- 흐름: 편집 → 2~3초마다 자동 임시저장(client_seq 증가) → [저장] 누르면 정식 기록에 반영(lock_version 확인) → 임시저장 삭제
-- ─────────────────────────────────────────────────────────────
CREATE TABLE form_drafts (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    site_id        BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    target_type    VARCHAR(20)  NOT NULL,                   -- 고치는 기록 종류
    target_id      BIGINT       NULL,                       -- NULL = 아직 저장 안 된 새 문서
    base_version   INT          NULL,                       -- 편집을 시작할 때 기록의 lock_version. 저장 시 달라졌으면 "다른 사람이 먼저 고쳤습니다"
    schema_version VARCHAR(20)  NOT NULL,
    title          VARCHAR(200) NOT NULL,                   -- "작성 중인 문서" 목록에 보일 이름
    draft_json     JSON         NOT NULL,
    client_seq     INT          NOT NULL DEFAULT 0,         -- 자동 저장 순번. 늦게 도착한 옛 요청이 새 내용을 덮지 않게 (UPDATE ... WHERE client_seq < :seq)
    -- 기존 기록은 사람당 임시저장 1개 (새 문서는 여러 개 가능: 아차사고 2건 동시 작성 등)
    existing_key   VARCHAR(60)  GENERATED ALWAYS AS (IF(target_id IS NULL, NULL, CONCAT(user_id, ':', target_type, ':', target_id))) STORED,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    expires_at     DATETIME(6)  NOT NULL DEFAULT (CURRENT_TIMESTAMP(6) + INTERVAL 30 DAY),   -- 30일 지난 임시저장은 정리 배치가 삭제
    PRIMARY KEY (id),
    UNIQUE KEY uk_fd_existing (existing_key),
    KEY idx_fd_user (user_id, updated_at),                  -- 내 작성 중 목록
    KEY idx_fd_target (target_type, target_id, updated_at), -- "김OO님이 편집 중" 표시 (최근 5분 내 다른 사람 임시저장)
    KEY idx_fd_expire (expires_at),
    CONSTRAINT fk_fd_site FOREIGN KEY (site_id) REFERENCES sites (id),
    CONSTRAINT fk_fd_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_fd_target  CHECK (target_type IN ('WORK', 'RISK', 'PERMIT', 'INSPECTION', 'TBM', 'NEAR_MISS', 'DAILY_LOG', 'CORRECTIVE_ACTION')),
    CONSTRAINT ck_fd_base    CHECK ((target_id IS NULL) = (base_version IS NULL)),   -- 기존 기록이면 기준 판 필수
    CONSTRAINT ck_fd_seq     CHECK (client_seq >= 0),
    CONSTRAINT ck_fd_json    CHECK (JSON_TYPE(draft_json) = 'OBJECT'),
    CONSTRAINT ck_fd_size    CHECK (JSON_STORAGE_SIZE(draft_json) <= 1048576),       -- 1MB 넘는 임시저장 거부 (사진은 첨부로)
    CONSTRAINT ck_fd_title   CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT ck_fd_expire  CHECK (expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────
-- 비동기 작업: PDF 출력, 수정 후 규칙 재판정, 감사 보고서, 안전일지 집계처럼 시간이 걸리는 일 (AI 실행은 agent_runs)
--  * @Async만 쓰면 서버가 재시작될 때 작업이 사라진다 → 테이블에 남겨 재시작 후 이어서 처리, 실패 시 재시도
--  * 화면은 이 행의 status·progress를 폴링(또는 완료 알림 DOCUMENT_READY)
--  * idempotency_key로 [PDF 출력] 두 번 눌러도 작업은 하나
--  * 작업자(스레드)는 SELECT ... WHERE status='QUEUED' AND run_after <= NOW(6) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED 로 하나씩 가져간다
-- ─────────────────────────────────────────────────────────────
CREATE TABLE async_jobs (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    site_id            BIGINT       NOT NULL,
    requested_by       BIGINT       NOT NULL,
    job_type           VARCHAR(20)  NOT NULL,
    target_type        VARCHAR(20)  NOT NULL,
    target_id          BIGINT       NOT NULL,
    target_version     INT          NOT NULL,               -- 요청 시점 기록의 lock_version (처리 중 내용이 바뀌면 실패 처리 후 다시 요청)
    params             JSON         NULL,                   -- 예: {"formType":"CONFINED_SPACE_PERMIT"}
    idempotency_key    VARCHAR(120) NOT NULL,               -- 예: EXPORT_PDF:PERMIT:5001:v7
    status             VARCHAR(10)  NOT NULL DEFAULT 'QUEUED',
    progress           TINYINT      NOT NULL DEFAULT 0,
    attempts           INT          NOT NULL DEFAULT 0,
    max_attempts       INT          NOT NULL DEFAULT 3,
    run_after          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),   -- 재시도 대기 (실패 시 뒤로 미룸)
    locked_by          VARCHAR(100) NULL,                   -- 처리 중인 서버 인스턴스
    result_document_id BIGINT       NULL,
    error_message      TEXT         NULL,
    created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at         DATETIME(6)  NULL,
    finished_at        DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_idem (idempotency_key),
    KEY idx_job_queue (status, run_after, id),              -- 작업 가져가기
    KEY idx_job_target (target_type, target_id, created_at),
    KEY idx_job_user (requested_by, created_at),
    CONSTRAINT fk_job_site FOREIGN KEY (site_id)            REFERENCES sites (id),
    CONSTRAINT fk_job_user FOREIGN KEY (requested_by)       REFERENCES users (id),
    CONSTRAINT fk_job_doc  FOREIGN KEY (result_document_id) REFERENCES generated_documents (id),
    CONSTRAINT ck_job_type     CHECK (job_type IN ('EXPORT_PDF', 'RULE_CHECK', 'AUDIT_REPORT', 'DAILY_LOG_BUILD')),
    CONSTRAINT ck_job_target   CHECK (target_type IN ('RISK', 'PERMIT', 'INSPECTION', 'TBM', 'DAILY_LOG', 'WORK', 'SITE')),
    CONSTRAINT ck_job_status   CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED', 'CANCELED')),
    CONSTRAINT ck_job_progress CHECK (progress BETWEEN 0 AND 100 AND (status <> 'DONE' OR progress = 100)),
    CONSTRAINT ck_job_attempts CHECK (attempts >= 0 AND max_attempts BETWEEN 1 AND 10 AND attempts <= max_attempts),
    CONSTRAINT ck_job_running  CHECK (status <> 'RUNNING' OR (locked_by IS NOT NULL AND started_at IS NOT NULL)),
    CONSTRAINT ck_job_finish   CHECK ((status IN ('DONE', 'FAILED', 'CANCELED')) = (finished_at IS NOT NULL)),
    CONSTRAINT ck_job_failed   CHECK (status <> 'FAILED' OR error_message IS NOT NULL),
    CONSTRAINT ck_job_pdf      CHECK (status <> 'DONE' OR job_type NOT IN ('EXPORT_PDF', 'AUDIT_REPORT') OR result_document_id IS NOT NULL),  -- 출력 완료면 결과 문서 필수
    CONSTRAINT ck_job_version  CHECK (target_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
