CREATE TABLE pipeline_runs (
    id UUID PRIMARY KEY,
    pipeline_id UUID NOT NULL REFERENCES pipelines(id) ON DELETE RESTRICT,
    pipeline_version_id UUID NOT NULL REFERENCES pipeline_versions(id) ON DELETE RESTRICT,
    branch_name VARCHAR(255) NOT NULL,
    trigger_type VARCHAR(16) NOT NULL CHECK (trigger_type IN ('MANUAL')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    requested_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    failure_message TEXT
);

CREATE INDEX idx_pipeline_runs_pipeline_requested ON pipeline_runs (pipeline_id, requested_at DESC);
CREATE INDEX idx_pipeline_runs_queue ON pipeline_runs (status, requested_at ASC) WHERE status = 'QUEUED';

CREATE TABLE pipeline_run_logs (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES pipeline_runs(id) ON DELETE CASCADE,
    stream VARCHAR(16) NOT NULL CHECK (stream IN ('SYSTEM', 'STDOUT', 'STDERR')),
    message TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_pipeline_run_logs_cursor ON pipeline_run_logs (run_id, id ASC);
