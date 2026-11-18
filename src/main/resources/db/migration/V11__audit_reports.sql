-- 감사보고서: 기간·조회 항목을 정하면 서버가 기록을 집계해 저장하고(GENERATING), 커밋 뒤 AI 서버(/api/v1/agent/audit-report)가
-- 항목별 요약과 수치 검증을 붙인다(DRAFT·REVIEW_REQUIRED, 실패면 FAILED).
-- 집계 원본(data_snapshot)은 만든 순간 그대로 남기고 고치지 않는다. 사람이 고치는 것은 제목·종합 의견·항목별 요약(items_json)뿐.
-- 삭제는 숨김(deleted_at)으로만 한다. 개선조치 출처(corrective_actions.source_type = 'AUDIT')로 쓰인다.
CREATE TABLE audit_reports (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    site_id              BIGINT       NOT NULL,
    title                VARCHAR(200) NOT NULL,
    date_from            DATE         NOT NULL,
    date_to              DATE         NOT NULL,
    query_types          VARCHAR(300) NOT NULL,                  -- 쉼표로 구분한 조회 항목 (화면에서 고른 순서)
    status               VARCHAR(20)  NOT NULL,                  -- GENERATING(AI 요약 중) | DRAFT | REVIEW_REQUIRED | FAILED
    overall_verification VARCHAR(10)  NULL,                      -- PASS | FAIL (AI 수치 검증), AI 실패면 NULL
    failure_reason       VARCHAR(500) NULL,
    summary              TEXT         NULL,                      -- 종합 의견 (사람이 씀)
    items_json           LONGTEXT     NOT NULL,                  -- 항목별 제목·요약·검증·집계·근거 (요약만 사람이 고침)
    data_snapshot        LONGTEXT     NOT NULL,                  -- 만든 순간의 집계 원본 (고치지 않음)
    created_by           BIGINT       NOT NULL,
    deleted_at           DATETIME(6)  NULL,
    deleted_by           BIGINT       NULL,
    lock_version         INT          NOT NULL DEFAULT 0,
    created_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_audr_site (site_id, deleted_at, created_at),
    CONSTRAINT fk_audr_site    FOREIGN KEY (site_id)    REFERENCES sites (id),
    CONSTRAINT fk_audr_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_audr_deleter FOREIGN KEY (deleted_by) REFERENCES users (id),
    CONSTRAINT ck_audr_status  CHECK (status IN ('GENERATING', 'DRAFT', 'REVIEW_REQUIRED', 'FAILED')),
    CONSTRAINT ck_audr_verify  CHECK (overall_verification IS NULL OR overall_verification IN ('PASS', 'FAIL')),
    CONSTRAINT ck_audr_period  CHECK (date_from <= date_to),
    CONSTRAINT ck_audr_deleted CHECK ((deleted_at IS NULL) = (deleted_by IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
