ALTER TABLE pipeline_runs
    ADD COLUMN claimed_by_worker_id VARCHAR(100),
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

CREATE INDEX idx_pipeline_runs_leases
    ON pipeline_runs (lease_expires_at)
    WHERE status = 'RUNNING';

UPDATE pipeline_runs
SET status = 'FAILED',
    completed_at = CURRENT_TIMESTAMP,
    failure_message = 'La ejecución estaba activa antes de habilitar leases de worker.'
WHERE status = 'RUNNING';
