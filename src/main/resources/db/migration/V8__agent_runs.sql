-- V8: 대화(이전 기록·최근 항목), 에이전트 실행, 단계 로그, 근거 인용
-- 담당: C 작성, 업무 소유 B
--
-- 관계: 대화 1 ── N 메시지 / 대화 1 ── N 실행 / 실행 1 ── N 단계
--  * 사용자 메시지 하나 = 실행 하나. 되묻기(WAITING_INPUT) 후 답변은 같은 대화의 다음 실행.
--  * 도구 결과 원본은 agent_steps, 메시지에는 요약만.
--  * 메시지 추가 시 대화 행을 SELECT ... FOR UPDATE로 잠그고 seq = message_count + 1 (동시 전송 시 순서 꼬임 방지)

CREATE TABLE agent_conversations (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    site_id           BIGINT       NOT NULL,
    user_id           BIGINT       NOT NULL,
    title             VARCHAR(100) NOT NULL,                -- 최근 항목에 보이는 이름 = 첫 사용자 메시지 앞부분 (사용자가 이름 바꾸기 가능)
    context_type      VARCHAR(20)  NULL,
    context_id        BIGINT       NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    pinned            BOOLEAN      NOT NULL DEFAULT FALSE,
    summary           TEXT         NULL,
    summary_until_seq INT          NULL,
    message_count     INT          NOT NULL DEFAULT 0,
    last_message_at   DATETIME(6)  NULL,
    deleted_at        DATETIME(6)  NULL,
    lock_version      INT          NOT NULL DEFAULT 0,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_conv_recent (user_id, deleted_at, pinned, last_message_at),
    KEY idx_conv_context (context_type, context_id),
    FULLTEXT KEY ft_conv_title (title) WITH PARSER ngram,
    CONSTRAINT fk_conv_site FOREIGN KEY (site_id) REFERENCES sites (id),
    CONSTRAINT fk_conv_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_conv_title   CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT ck_conv_context CHECK (context_type IS NULL OR context_type IN ('WORK', 'PERMIT', 'NEAR_MISS', 'INSPECTION', 'RISK')),
    CONSTRAINT ck_conv_ctx_pair CHECK ((context_type IS NULL) = (context_id IS NULL)),
    CONSTRAINT ck_conv_status  CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_conv_count   CHECK (message_count >= 0),
    CONSTRAINT ck_conv_summary CHECK (summary_until_seq IS NULL OR (summary IS NOT NULL AND summary_until_seq BETWEEN 1 AND message_count)),
    CONSTRAINT ck_conv_last    CHECK ((message_count = 0) = (last_message_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_messages (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT      NOT NULL,
    seq             INT         NOT NULL,
    role            VARCHAR(10) NOT NULL,
    content         MEDIUMTEXT  NULL,
    content_json    JSON        NULL,
    tool_name       VARCHAR(40) NULL,
    tool_call_id    VARCHAR(64) NULL,
    run_id          BIGINT      NULL,                       -- FK는 아래 (agent_runs 생성 후)
    token_count     INT         NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_seq (conversation_id, seq),
    UNIQUE KEY uk_msg_toolcall (conversation_id, tool_call_id, role),   -- 같은 호출의 요청·결과는 각각 한 번
    KEY idx_msg_run (run_id),
    CONSTRAINT fk_msg_conv FOREIGN KEY (conversation_id) REFERENCES agent_conversations (id) ON DELETE CASCADE,
    CONSTRAINT ck_msg_role CHECK (role IN ('USER', 'ASSISTANT', 'TOOL')),
    CONSTRAINT ck_msg_seq  CHECK (seq >= 1),
    CONSTRAINT ck_msg_body CHECK (content IS NOT NULL OR content_json IS NOT NULL),            -- 빈 메시지 금지
    CONSTRAINT ck_msg_tool CHECK (role <> 'TOOL' OR (tool_name IS NOT NULL AND tool_call_id IS NOT NULL)),
    CONSTRAINT ck_msg_tokens CHECK (token_count IS NULL OR token_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_runs (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    site_id         BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    conversation_id BIGINT       NULL,                      -- 자동 실행(아차사고 태깅, 일지 생성)은 NULL
    task_type       VARCHAR(30)  NOT NULL,
    mode            VARCHAR(10)  NOT NULL,
    network_mode    VARCHAR(10)  NOT NULL,
    model           VARCHAR(60)  NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING',
    input_json      JSON         NULL,
    output_json     JSON         NULL,
    target_type     VARCHAR(20)  NULL,
    target_id       BIGINT       NULL,
    total_ms        INT          NULL,
    input_tokens    INT          NULL,
    output_tokens   INT          NULL,
    error_message   TEXT         NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at     DATETIME(6)  NULL,
    PRIMARY KEY (id),
    KEY idx_ar_user (user_id, created_at),
    KEY idx_ar_site_task (site_id, task_type, created_at),
    KEY idx_ar_conv (conversation_id, created_at),
    KEY idx_ar_status (status, created_at),                     -- 멈춘 실행(PROCESSING 장기 체류) 정리용
    CONSTRAINT fk_ar_site FOREIGN KEY (site_id)         REFERENCES sites (id),
    CONSTRAINT fk_ar_user FOREIGN KEY (user_id)         REFERENCES users (id),
    CONSTRAINT fk_ar_conv FOREIGN KEY (conversation_id) REFERENCES agent_conversations (id),
    CONSTRAINT ck_ar_task    CHECK (task_type IN ('WORK_REGISTER', 'RISK_DRAFT', 'PERMIT_DRAFT', 'INSPECTION_DRAFT', 'TBM_DRAFT',
                                                  'NEAR_MISS_TAG', 'PHOTO_CHECK', 'RECORD_QUERY', 'DAILY_LOG', 'AUDIT_REPORT')),
    CONSTRAINT ck_ar_mode    CHECK (mode IN ('VERIFY', 'GUIDE')),
    CONSTRAINT ck_ar_network CHECK (network_mode IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_ar_status  CHECK (status IN ('PROCESSING', 'WAITING_INPUT', 'DONE', 'FAILED')),
    CONSTRAINT ck_ar_finish  CHECK ((status IN ('DONE', 'FAILED')) = (finished_at IS NOT NULL)),  -- 끝났으면 종료 시각 필수
    CONSTRAINT ck_ar_error   CHECK (status <> 'FAILED' OR error_message IS NOT NULL),
    CONSTRAINT ck_ar_target  CHECK ((target_type IS NULL) = (target_id IS NULL)),
    CONSTRAINT ck_ar_nums    CHECK (COALESCE(total_ms, 0) >= 0 AND COALESCE(input_tokens, 0) >= 0 AND COALESCE(output_tokens, 0) >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE agent_messages
    ADD CONSTRAINT fk_msg_run FOREIGN KEY (run_id) REFERENCES agent_runs (id);

CREATE TABLE agent_steps (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    run_id      BIGINT      NOT NULL,
    seq         INT         NOT NULL,
    step_type   VARCHAR(20) NOT NULL,
    tool_name   VARCHAR(40) NULL,
    input_json  JSON        NULL,
    output_json JSON        NULL,
    status      VARCHAR(10) NOT NULL DEFAULT 'DONE',
    latency_ms  INT         NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_as_seq (run_id, seq),
    CONSTRAINT fk_as_run FOREIGN KEY (run_id) REFERENCES agent_runs (id) ON DELETE CASCADE,
    CONSTRAINT ck_as_type   CHECK (step_type IN ('PLAN', 'TOOL_CALL', 'SUFFICIENCY', 'JUDGE', 'VERIFY', 'OUTPUT', 'ASK_USER')),
    CONSTRAINT ck_as_tool   CHECK ((step_type = 'TOOL_CALL') = (tool_name IS NOT NULL)),
    CONSTRAINT ck_as_toolname CHECK (tool_name IS NULL OR tool_name IN (
        'search_accidents', 'search_guides', 'lookup_msds', 'classify_work', 'check_rules',
        'check_photo', 'fill_form', 'export_form', 'search_records', 'request_approval')),
    CONSTRAINT ck_as_status CHECK (status IN ('RUNNING', 'DONE', 'FAILED')),
    CONSTRAINT ck_as_seq    CHECK (seq >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE evidence_citations (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    run_id      BIGINT       NOT NULL,
    step_id     BIGINT       NULL,
    target_type VARCHAR(20)  NULL,
    target_id   BIGINT       NULL,
    source_type VARCHAR(20)  NOT NULL,
    -- 지식 데이터는 FastAPI 전용 DB(agent_kb)에 있다. 서비스 경계를 넘는 FK는 걸지 않고
    -- Qdrant 점 id만 느슨하게 참조한다. 원본이 바뀌거나 지워져도 근거가 남도록 snippet을 필수로 보관.
    kb_collection VARCHAR(40) NULL,                         -- kb_accidents | kb_guides
    kb_point_id   CHAR(36)    NULL,                         -- agent_kb.kb_chunks.qdrant_point_id 와 같은 값
    rule_id     BIGINT       NULL,
    title       VARCHAR(300) NOT NULL,
    locator     VARCHAR(100) NULL,
    url         VARCHAR(500) NULL,
    snippet     TEXT         NULL,                          -- 인용 당시 원문 (원본이 바뀌어도 남음)
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_ev_run (run_id),
    KEY idx_ev_target (target_type, target_id),
    KEY idx_ev_point (kb_point_id),
    CONSTRAINT fk_ev_run  FOREIGN KEY (run_id)  REFERENCES agent_runs (id),
    CONSTRAINT fk_ev_step FOREIGN KEY (step_id) REFERENCES agent_steps (id),
    CONSTRAINT fk_ev_rule FOREIGN KEY (rule_id) REFERENCES rules (id),
    CONSTRAINT ck_ev_source CHECK (source_type IN ('ACCIDENT_REPORT', 'SIF', 'KOSHA_GUIDE', 'RISK_GUIDE', 'CHECKLIST',
                                                   'LAW', 'MSDS', 'NEAR_MISS', 'RULE', 'RECORD')),
    CONSTRAINT ck_ev_target CHECK ((target_type IS NULL) = (target_id IS NULL)),
    CONSTRAINT ck_ev_rule   CHECK (source_type <> 'RULE' OR rule_id IS NOT NULL),
    CONSTRAINT ck_ev_kb_pair CHECK ((kb_collection IS NULL) = (kb_point_id IS NULL)),
    CONSTRAINT ck_ev_kb_coll CHECK (kb_collection IS NULL OR kb_collection IN ('kb_accidents', 'kb_guides')),
    CONSTRAINT ck_ev_kb_uuid CHECK (kb_point_id IS NULL OR kb_point_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CONSTRAINT ck_ev_kb_snip CHECK (kb_point_id IS NULL OR snippet IS NOT NULL),    -- 느슨한 참조이므로 원문 필수
    -- 검색 문서에서 온 근거(사고사례·지침)는 반드시 어느 청크인지 남긴다
    CONSTRAINT ck_ev_kb_req  CHECK (source_type NOT IN ('ACCIDENT_REPORT', 'SIF', 'KOSHA_GUIDE', 'RISK_GUIDE', 'CHECKLIST') OR kb_point_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- AI 결과에 대한 사용자 평가 (3주차 튜닝 자료). 사람이 고친 내용 자체는 record_history의 AI_DRAFT → UPDATE 비교로 본다
CREATE TABLE agent_feedback (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    run_id      BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    rating      VARCHAR(5)   NOT NULL,
    reason_code VARCHAR(20)  NULL,
    comment     VARCHAR(500) NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_fb_once (run_id, user_id),                -- 한 실행에 한 사람 한 번 (바꾸면 UPDATE)
    KEY idx_fb_reason (rating, reason_code, created_at),
    CONSTRAINT fk_fb_run  FOREIGN KEY (run_id)  REFERENCES agent_runs (id),
    CONSTRAINT fk_fb_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_fb_rating CHECK (rating IN ('UP', 'DOWN')),
    CONSTRAINT ck_fb_reason CHECK (reason_code IS NULL OR reason_code IN ('WRONG_FACT', 'MISSING_HAZARD', 'BAD_CITATION',
                                                                          'WRONG_RULE', 'FORMAT', 'TOO_SLOW', 'OTHER')),
    CONSTRAINT ck_fb_down   CHECK (rating = 'UP' OR reason_code IS NOT NULL)   -- 싫어요는 이유 필수 (튜닝에 쓰려면)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 앞 버전에서 미뤄둔 agent_run_id 연결
ALTER TABLE works             ADD CONSTRAINT fk_works_run   FOREIGN KEY (agent_run_id) REFERENCES agent_runs (id);
ALTER TABLE risk_assessments  ADD CONSTRAINT fk_risk_run    FOREIGN KEY (agent_run_id) REFERENCES agent_runs (id);
ALTER TABLE permits           ADD CONSTRAINT fk_permits_run FOREIGN KEY (agent_run_id) REFERENCES agent_runs (id);
ALTER TABLE tbm_records       ADD CONSTRAINT fk_tbm_run     FOREIGN KEY (agent_run_id) REFERENCES agent_runs (id);
ALTER TABLE photo_checks      ADD CONSTRAINT fk_photo_run   FOREIGN KEY (agent_run_id) REFERENCES agent_runs (id);
ALTER TABLE daily_safety_logs ADD CONSTRAINT fk_dsl_run     FOREIGN KEY (agent_run_id) REFERENCES agent_runs (id);
ALTER TABLE rule_evaluations  ADD CONSTRAINT fk_eval_run    FOREIGN KEY (agent_run_id) REFERENCES agent_runs (id);
ALTER TABLE generated_documents ADD CONSTRAINT fk_gd_run     FOREIGN KEY (agent_run_id) REFERENCES agent_runs (id);
