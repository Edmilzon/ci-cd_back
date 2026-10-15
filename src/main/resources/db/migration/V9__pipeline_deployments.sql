CREATE TABLE pipeline_deployments (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    pipeline_run_id UUID NOT NULL REFERENCES pipeline_runs(id) ON DELETE CASCADE,
    job_id VARCHAR(40) NOT NULL,
    environment_name VARCHAR(64) NOT NULL,
    source_commit_sha VARCHAR(40) NOT NULL,
    github_deployment_id BIGINT,
    github_html_url TEXT,
    status VARCHAR(24) NOT NULL,
    requested_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    approved_by_user_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    approved_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_pipeline_deployments_run_job UNIQUE (pipeline_run_id, job_id)
);

CREATE INDEX idx_pipeline_deployments_project_requested ON pipeline_deployments (project_id, requested_at DESC);
CREATE INDEX idx_pipeline_deployments_status ON pipeline_deployments (status, requested_at);

CREATE TABLE pipeline_deployment_locks (
    github_repository_id BIGINT NOT NULL,
    environment_name VARCHAR(64) NOT NULL,
    PRIMARY KEY (github_repository_id, environment_name)
);

ALTER TABLE pipeline_runs ADD CONSTRAINT fk_pipeline_runs_rollback_deployment
    FOREIGN KEY (rollback_of_deployment_id) REFERENCES pipeline_deployments(id) ON DELETE SET NULL;
