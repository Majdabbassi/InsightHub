-- =====================================================================
-- V1: schema bootstrap
-- =====================================================================
-- Mirrors the JPA entities under com.dataanalytics.backend.model so Flyway
-- (neither Hibernate's ddl-auto=update) owns the schema. Applied once on
-- every fresh database. Pre-existing local databases that were created by
-- the earlier ddl-auto=update are BASELINED instead (see
-- spring.flyway.baseline-on-migrate), which makes V1 a no-op for them.

CREATE TABLE users (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    email      VARCHAR(255) NOT NULL,
    password   VARCHAR(100) NOT NULL,
    full_name  VARCHAR(100) NOT NULL,
    role       VARCHAR(20)  NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE projects (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100)  NOT NULL,
    description VARCHAR(1000),
    owner_id    BIGINT        NOT NULL,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_projects_owner FOREIGN KEY (owner_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_projects_owner ON projects (owner_id);

CREATE TABLE datasets (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    name               VARCHAR(255)  NOT NULL,
    original_filename  VARCHAR(255)  NOT NULL,
    stored_file_path   VARCHAR(512)  NOT NULL,
    file_size_bytes    BIGINT        NOT NULL,
    row_count          INT,
    column_count       INT,
    project_id         BIGINT        NOT NULL,
    source_dataset_id  BIGINT,
    is_cleaned_version TINYINT(1)    NOT NULL,
    uploaded_at        DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_datasets_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_datasets_source FOREIGN KEY (source_dataset_id) REFERENCES datasets (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_datasets_project ON datasets (project_id);

CREATE TABLE analysis_results (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    dataset_id           BIGINT       NOT NULL,
    row_count            INT          NOT NULL,
    column_count         INT          NOT NULL,
    duplicate_row_count  INT          NOT NULL,
    total_missing_values BIGINT       NOT NULL,
    column_stats         TEXT         NOT NULL,
    correlations_json    TEXT,
    data_quality_json    TEXT,
    analyzed_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_analysis_dataset UNIQUE (dataset_id),
    CONSTRAINT fk_analysis_dataset FOREIGN KEY (dataset_id) REFERENCES datasets (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE dataset_relationships (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    project_id        BIGINT       NOT NULL,
    dataset_a_id      BIGINT       NOT NULL,
    dataset_b_id      BIGINT       NOT NULL,
    shared_column_a   VARCHAR(255) NOT NULL,
    shared_column_b   VARCHAR(255) NOT NULL,
    match_percentage  DOUBLE,
    status            VARCHAR(20)  NOT NULL,
    relationship_type VARCHAR(20)  NOT NULL DEFAULT 'FOREIGN_KEY',
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_relationships_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_relationships_dataset_a FOREIGN KEY (dataset_a_id) REFERENCES datasets (id),
    CONSTRAINT fk_relationships_dataset_b FOREIGN KEY (dataset_b_id) REFERENCES datasets (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_relationships_project ON dataset_relationships (project_id);

CREATE TABLE dataset_insight_snapshots (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    dataset_id   BIGINT      NOT NULL,
    insight_type VARCHAR(30) NOT NULL,
    result_json  TEXT        NOT NULL,
    computed_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_insight_snapshot_dataset_type UNIQUE (dataset_id, insight_type),
    CONSTRAINT fk_insight_snapshot_dataset FOREIGN KEY (dataset_id) REFERENCES datasets (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE chat_conversations (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    project_id BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_chat_conversation_project UNIQUE (project_id),
    CONSTRAINT fk_chat_conversation_project FOREIGN KEY (project_id) REFERENCES projects (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE chat_messages (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT      NOT NULL,
    role            VARCHAR(20) NOT NULL,
    content         TEXT        NOT NULL,
    used_sql        TEXT,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_chat_message_conversation FOREIGN KEY (conversation_id)
        REFERENCES chat_conversations (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_chat_messages_conversation_created
    ON chat_messages (conversation_id, created_at);

CREATE TABLE cleaning_jobs (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    source_dataset_id    BIGINT      NOT NULL,
    resulting_dataset_id BIGINT,
    applied_actions      TEXT,
    status               VARCHAR(20) NOT NULL,
    rows_before          INT,
    rows_after           INT,
    values_filled        INT,
    error_message        TEXT,
    created_at           DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_cleaning_jobs_source FOREIGN KEY (source_dataset_id) REFERENCES datasets (id),
    CONSTRAINT fk_cleaning_jobs_result FOREIGN KEY (resulting_dataset_id) REFERENCES datasets (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;