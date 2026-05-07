CREATE TABLE IF NOT EXISTS users (
    id                      UUID         NOT NULL,
    github_id               BIGINT       NOT NULL,
    login                   VARCHAR(255) NOT NULL,
    name                    VARCHAR(255),
    email                   VARCHAR(255),
    avatar_url              VARCHAR(512),
    access_token            TEXT         NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_login              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_github_id UNIQUE (github_id)
);

CREATE INDEX IF NOT EXISTS idx_users_login ON users (login);

-- Track which GitHub user triggered each analysis
ALTER TABLE pr_analysis_results
    ADD COLUMN IF NOT EXISTS analyzed_by VARCHAR(255);
