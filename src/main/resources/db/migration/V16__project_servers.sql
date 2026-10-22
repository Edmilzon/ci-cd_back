CREATE TABLE project_servers (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    host VARCHAR(253) NOT NULL,
    port INTEGER NOT NULL,
    username VARCHAR(100) NOT NULL,
    password_ciphertext TEXT NOT NULL,
    last_status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    last_connected_at TIMESTAMPTZ,
    created_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    updated_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_project_servers_project_name UNIQUE (project_id, name),
    CONSTRAINT ck_project_servers_port CHECK (port BETWEEN 1 AND 65535)
);

CREATE INDEX idx_project_servers_project_id ON project_servers(project_id);
