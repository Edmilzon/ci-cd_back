# CDCI operations runbook

Operational baseline for the internal beta. The API does not claim an availability SLA; review the alert thresholds and recovery times against measured service behavior before a public release.

## Health probes and alerts

- Poll `GET /api/health/live` every 30 seconds. Alert after three consecutive non-2xx responses; restart only after capturing the service logs.
- Poll `GET /api/health/ready` every 30 seconds. It returns only `UP`/`DOWN` for the API and PostgreSQL. Alert after three consecutive failures. Check PostgreSQL availability, disk, connection exhaustion, then application logs.
- Alert when the latest encrypted database backup is older than 26 hours or when `ops/backup-postgres.sh` exits non-zero. Run `ops/check-backup-age.sh` from the host monitoring schedule; its non-zero exit is the alert signal.
- Alert on PostgreSQL volume usage above 80%, or when a queued pipeline's oldest request is over 15 minutes. Queue query: `SELECT count(*), min(requested_at) FROM pipeline_runs WHERE status = 'QUEUED';`. Expired worker leases are failed by CDCI's 30-second recovery schedule; investigate the runner and its logs before retrying.
- Capture backend/runner logs and timestamps with each incident. Never include database URLs, GitHub tokens, runner tokens, encryption keys, or secret values in tickets or logs.

## Retention

- Terminal pipeline runs and their logs are purged after 90 days (`CDCI_RUN_RETENTION_DAYS`). Active runs are never purged.
- Audit events are purged after 365 days (`CDCI_AUDIT_RETENTION_DAYS`). Keep audit retention aligned with the organization's privacy and incident-response requirements.
- CDCI does not persist pipeline artifacts. GitHub's deployment history is managed by GitHub and its account settings.
- PostgreSQL backups default to 30 days (`CDCI_BACKUP_RETENTION_DAYS`). The backup directory must be a durable, encrypted, off-host mount; runner work volumes are not backup storage.

## Encrypted PostgreSQL backups

Create a dedicated `age` recipient for the environment. Keep its private identity in a separate secrets vault and recovery location; the backup host needs only the public recipient. Keep the CDCI GitHub-token and pipeline-secret encryption keys in that same separate recovery plan, never beside the database archive.

Provide `CDCI_BACKUP_DATABASE_URL` as a libpq `postgresql://` URI, `CDCI_BACKUP_AGE_RECIPIENT`, and `CDCI_BACKUP_DIR`, then schedule `ops/backup-postgres.sh` daily. `CDCI_BACKUP_DATABASE_URL` is separate from CDCI's JDBC `CDCI_DATABASE_URL`. Prefer a URI without an embedded password and use a mode-0600 `PGPASSFILE` for credentials. The script streams a PostgreSQL custom-format dump directly into `age`, writes with restrictive file permissions, publishes it atomically, and removes expired CDCI backup files. Configure host monitoring to alert on a non-zero exit and run `ops/check-backup-age.sh` at least hourly.

## Restore drill

Run a monthly restore drill into a new, empty, isolated, non-production PostgreSQL database. The target must be empty; the restore script checks for existing public relations and never drops or cleans them. Use a libpq `postgresql://` URI with a mode-0600 `PGPASSFILE`, not the application's JDBC URL. Because the restore uses `--no-owner`, use the same database role as CDCI's runtime account so the restored objects are accessible to the application.

```sh
CDCI_BACKUP_AGE_IDENTITY=/secure/cdci-backup.agekey \
CDCI_RESTORE_DATABASE_URL='postgresql://restore-user@restore-host/cdci_restore' \
CDCI_RESTORE_CONFIRM_EMPTY=YES \
ops/restore-postgres.sh /mnt/cdci-backups/cdci-postgres-<timestamp>.dump.age
```

Record the backup age, restore duration, resulting Flyway rank, and whether the application starts against the restored database. If recovery is needed, pause API and runner traffic, restore into a separately prepared empty database, verify Flyway and key availability, update the managed database URL, then bring up API and runner and inspect their health probes. Do not point the restore script at production; it deliberately refuses a non-empty public schema.

## Runner and deployment incidents

Use the runner's container logs and CDCI run logs to identify the failed step. A missing heartbeat causes the lease recovery job to fail the run; verify Docker capacity and runner authentication before queuing a new run. For deployment incidents, inspect the project's deployment history and GitHub Deployment status, confirm the pinned commit SHA, and use CDCI rollback only after the project owner approves the recovery plan. Production rollback still requires an independent approval.
