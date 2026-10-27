-- V1: 기존 미니 프로젝트 스키마 (기준선). 이미 기동 중인 DB는 baseline으로 건너뜀

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS documents (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    stored_path   VARCHAR(500) NOT NULL,
    file_size     BIGINT       NOT NULL,
    page_count    INT          NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED',  -- UPLOADED|PROCESSING|DONE|FAILED
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_documents_user (user_id, created_at),
    CONSTRAINT fk_documents_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ocr_runs (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    document_id    BIGINT        NOT NULL,
    engine         VARCHAR(20)   NOT NULL,                   -- AUTO|PYMUPDF|PADDLE|TESSERACT|OLLAMA|OPENAI
    network_mode   VARCHAR(10)   NOT NULL,                   -- CLOSED|OPEN
    status         VARCHAR(20)   NOT NULL DEFAULT 'PROCESSING', -- PROCESSING|DONE|FAILED
    extract_images BOOLEAN       NOT NULL DEFAULT FALSE,
    total_ms       INT           NULL,
    avg_confidence DECIMAL(5,4)  NULL,
    error_message  TEXT          NULL,
    created_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at    DATETIME(6)   NULL,
    summary TEXT NULL,
    PRIMARY KEY (id),
    KEY idx_runs_document (document_id, created_at),
    CONSTRAINT fk_runs_document FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ocr_pages (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    run_id        BIGINT       NOT NULL,
    page_no       INT          NOT NULL,
    source        VARCHAR(20)  NOT NULL,                     -- TEXT_LAYER|OCR
    text          LONGTEXT     NULL,
    confidence    DECIMAL(5,4) NULL,
    processing_ms INT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pages_run_page (run_id, page_no),
    CONSTRAINT fk_pages_run FOREIGN KEY (run_id) REFERENCES ocr_runs (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ocr_images (
    id        BIGINT       NOT NULL AUTO_INCREMENT,
    run_id    BIGINT       NOT NULL,
    page_no   INT          NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    format    VARCHAR(10)  NOT NULL DEFAULT 'png',
    width     INT          NULL,
    height    INT          NULL,
    PRIMARY KEY (id),
    KEY idx_images_run (run_id),
    CONSTRAINT fk_images_run FOREIGN KEY (run_id) REFERENCES ocr_runs (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

