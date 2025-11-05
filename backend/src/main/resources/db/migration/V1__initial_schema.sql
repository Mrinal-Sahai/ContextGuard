-- Reviews table
CREATE TABLE reviews (
    id BIGSERIAL PRIMARY KEY,
    platform VARCHAR(50) NOT NULL,
    owner VARCHAR(255) NOT NULL,
    repository VARCHAR(255) NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    title VARCHAR(500) NOT NULL,
    author VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_activity_at TIMESTAMP,
    metadata JSONB,
    CONSTRAINT unique_review UNIQUE (platform, owner, repository, external_id)
);

-- Snapshots table
CREATE TABLE snapshots (
    id BIGSERIAL PRIMARY KEY,
    review_id BIGINT NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    commit_list JSONB,
    pr_body TEXT,
    diff_url VARCHAR(500),
 ticket_ids TEXT[],
    created_by VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    summary TEXT,
    why TEXT,
    risks TEXT[],
    review_checklist TEXT[],
    context_score INTEGER
);

-- Notifications table
CREATE TABLE notifications (
    id BIGSERIAL PRIMARY KEY,
    review_id BIGINT NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    type VARCHAR(50) NOT NULL,
    message TEXT,
    sent_at TIMESTAMP NOT NULL DEFAULT NOW(),
    delivery_status VARCHAR(20)
);
