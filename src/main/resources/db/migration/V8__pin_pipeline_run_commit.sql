ALTER TABLE pipeline_runs ADD COLUMN source_commit_sha VARCHAR(40);
ALTER TABLE pipeline_runs ADD COLUMN rollback_environment VARCHAR(64);
ALTER TABLE pipeline_runs ADD COLUMN rollback_of_deployment_id UUID;
