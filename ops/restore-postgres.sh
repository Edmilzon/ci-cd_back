#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

archive=${1:-}
identity=${CDCI_BACKUP_AGE_IDENTITY:-}
target_url=${CDCI_RESTORE_DATABASE_URL:-}

if [[ -z "$archive" || -z "$identity" || -z "$target_url" ]]; then
  echo "Usage: CDCI_BACKUP_AGE_IDENTITY=/secure/age.key CDCI_RESTORE_DATABASE_URL=postgresql://... CDCI_RESTORE_CONFIRM_EMPTY=YES $0 backup.dump.age" >&2
  exit 2
fi
if [[ "$target_url" != postgres://* && "$target_url" != postgresql://* ]]; then
  echo "CDCI_RESTORE_DATABASE_URL must be a libpq postgresql:// connection URI, not a JDBC URL." >&2
  exit 2
fi
if [[ "${CDCI_RESTORE_CONFIRM_EMPTY:-}" != "YES" ]]; then
  echo "Set CDCI_RESTORE_CONFIRM_EMPTY=YES after choosing a new, empty non-production database." >&2
  exit 2
fi
for command in age pg_restore psql; do
  command -v "$command" >/dev/null 2>&1 || { echo "Required command not found: $command" >&2; exit 2; }
done
if [[ ! -f "$archive" || ! -r "$archive" || ! -f "$identity" || ! -r "$identity" ]]; then
  echo "The encrypted archive or age identity is not readable." >&2
  exit 2
fi

dump_tmp=$(mktemp "${TMPDIR:-/tmp}/cdci-restore.XXXXXXXX")
trap 'rm -f -- "$dump_tmp"' EXIT
chmod 600 "$dump_tmp"
age --decrypt --identity "$identity" --output "$dump_tmp" "$archive"
pg_restore --list "$dump_tmp" >/dev/null

existing_objects=$(psql --no-psqlrc --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --dbname="$target_url" \
  --command="SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')")
existing_objects=${existing_objects//[[:space:]]/}
if [[ "$existing_objects" != "0" ]]; then
  echo "Restore stopped: the target database has existing public relations. Use an empty database." >&2
  exit 1
fi

pg_restore --exit-on-error --single-transaction --no-owner --no-acl \
  --dbname="$target_url" "$dump_tmp"
schema_rank=$(psql --no-psqlrc --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --dbname="$target_url" \
  --command="SELECT max(installed_rank) FROM flyway_schema_history")
schema_rank=${schema_rank//[[:space:]]/}
if [[ -z "$schema_rank" ]]; then
  echo "Restore stopped: no Flyway migrations were found in the target database." >&2
  exit 1
fi
echo "Restore completed in the empty target database; Flyway installed rank: $schema_rank"
