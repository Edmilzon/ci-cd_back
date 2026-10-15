ALTER TABLE access_audit_events
    ADD COLUMN project_id UUID REFERENCES projects(id) ON DELETE SET NULL;

UPDATE access_audit_events audit_event
SET project_id = project_row.id
FROM projects project_row
WHERE audit_event.project_id IS NULL
  AND audit_event.target_type = 'PROJECT'
  AND audit_event.target_id = project_row.id::text;

UPDATE access_audit_events audit_event
SET project_id = membership_row.project_id
FROM project_members membership_row
WHERE audit_event.project_id IS NULL
  AND audit_event.target_type = 'PROJECT_MEMBER'
  AND audit_event.target_id = membership_row.id::text;

UPDATE access_audit_events audit_event
SET project_id = repository_row.project_id
FROM project_repositories repository_row
WHERE audit_event.project_id IS NULL
  AND audit_event.target_type = 'PROJECT_REPOSITORY'
  AND audit_event.target_id = repository_row.id::text;

UPDATE access_audit_events audit_event
SET project_id = pipeline_row.project_id
FROM pipelines pipeline_row
WHERE audit_event.project_id IS NULL
  AND audit_event.target_type = 'PIPELINE'
  AND audit_event.target_id = pipeline_row.id::text;

UPDATE access_audit_events audit_event
SET project_id = pipeline_row.project_id
FROM pipeline_runs run_row
JOIN pipelines pipeline_row ON pipeline_row.id = run_row.pipeline_id
WHERE audit_event.project_id IS NULL
  AND audit_event.target_type = 'PIPELINE_RUN'
  AND audit_event.target_id = run_row.id::text;

UPDATE access_audit_events audit_event
SET project_id = deployment_row.project_id
FROM pipeline_deployments deployment_row
WHERE audit_event.project_id IS NULL
  AND audit_event.target_type = 'PIPELINE_DEPLOYMENT'
  AND audit_event.target_id = deployment_row.id::text;

UPDATE access_audit_events audit_event
SET project_id = project_row.id
FROM projects project_row
WHERE audit_event.project_id IS NULL
  AND audit_event.target_type = 'PROJECT_SECRET'
  AND split_part(audit_event.target_id, ':', 1) = project_row.id::text;

CREATE INDEX idx_access_audit_events_project_created
    ON access_audit_events (project_id, created_at DESC, id DESC);
