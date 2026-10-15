CREATE TABLE pipelines (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    project_repository_id UUID NOT NULL REFERENCES project_repositories(id) ON DELETE RESTRICT,
    name VARCHAR(100) NOT NULL,
    current_version INTEGER NOT NULL CHECK (current_version >= 1),
    created_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_pipelines_project_updated ON pipelines (project_id, updated_at DESC);
CREATE INDEX idx_pipelines_repository ON pipelines (project_repository_id);

CREATE TABLE pipeline_versions (
    id UUID PRIMARY KEY,
    pipeline_id UUID NOT NULL REFERENCES pipelines(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL CHECK (version_number >= 1),
    yaml_definition TEXT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    created_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_pipeline_versions_number UNIQUE (pipeline_id, version_number)
);

CREATE INDEX idx_pipeline_versions_history ON pipeline_versions (pipeline_id, version_number DESC);
