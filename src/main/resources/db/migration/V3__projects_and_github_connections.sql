CREATE TABLE projects (
    id UUID PRIMARY KEY,
    slug VARCHAR(100) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    created_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_projects_slug_ci ON projects (LOWER(slug));
CREATE INDEX idx_projects_updated_at ON projects (updated_at DESC) WHERE archived = FALSE;

CREATE TABLE project_members (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    access_level VARCHAR(16) NOT NULL CHECK (access_level IN ('OWNER', 'MAINTAINER', 'DEVELOPER', 'VIEWER')),
    added_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_project_members_project_user UNIQUE (project_id, user_id)
);

CREATE INDEX idx_project_members_user_id ON project_members (user_id, project_id);

CREATE TABLE github_connections (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    github_user_id BIGINT NOT NULL,
    github_login VARCHAR(39) NOT NULL,
    access_token_ciphertext TEXT NOT NULL,
    refresh_token_ciphertext TEXT,
    access_token_expires_at TIMESTAMPTZ,
    refresh_token_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE project_repositories (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    github_connection_id UUID NOT NULL REFERENCES github_connections(id) ON DELETE RESTRICT,
    github_repository_id BIGINT NOT NULL,
    full_name VARCHAR(200) NOT NULL,
    html_url VARCHAR(500) NOT NULL,
    default_branch VARCHAR(255) NOT NULL DEFAULT 'main',
    private_repository BOOLEAN NOT NULL DEFAULT FALSE,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    created_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_project_repositories_github UNIQUE (project_id, github_repository_id)
);

CREATE INDEX idx_project_repositories_project_id ON project_repositories (project_id, created_at DESC);
CREATE INDEX idx_project_repositories_connection_id ON project_repositories (github_connection_id);
