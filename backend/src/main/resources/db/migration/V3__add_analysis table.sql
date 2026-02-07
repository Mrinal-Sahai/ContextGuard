CREATE TABLE IF NOT EXISTS pr_analysis_results (
    id UUID NOT NULL,
    owner VARCHAR(255) NOT NULL,
    repo VARCHAR(255) NOT NULL,
    pr_number INTEGER NOT NULL,
    intelligence_json TEXT,
    analyzed_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_pr_analysis_results PRIMARY KEY (id),
    CONSTRAINT uq_pr_analysis_owner_repo_pr UNIQUE (owner, repo, pr_number)
);


CREATE INDEX IF NOT EXISTS idx_pr_analysis_owner_repo_pr
ON pr_analysis_results (owner, repo, pr_number);

CREATE INDEX IF NOT EXISTS idx_pr_analysis_analyzed_at
ON pr_analysis_results (analyzed_at);