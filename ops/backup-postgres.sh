#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

database_url=${CDCI_BACKUP_DATABASE_URL:-}
backup_dir=${CDCI_BACKUP_DIR:-/var/backups/cdci/postgres}
recipient=${CDCI_BACKUP_AGE_RECIPIENT:-}
retention_days=${CDCI_BACKUP_RETENTION_DAYS:-30}

if [[ -z "$database_url" || -z "$recipient" ]]; then
  echo "Set CDCI_BACKUP_DATABASE_URL and CDCI_BACKUP_AGE_RECIPIENT." >&2
  exit 2
fi
if [[ "$database_url" != postgres://* && "$database_url" != postgresql://* ]]; then
  echo "CDCI_BACKUP_DATABASE_URL must be a libpq postgresql:// connection URI, not a JDBC URL." >&2
  exit 2
fi
if [[ ! "$retention_days" =~ ^[1-9][0-9]{0,3}$ ]] || (( retention_days > 3650 )); then
  echo "CDCI_BACKUP_RETENTION_DAYS must be between 1 and 3650." >&2
  exit 2
fi
for command in pg_dump age find; do
  command -v "$command" >/dev/null 2>&1 || { echo "Required command not found: $command" >&2; exit 2; }
done

mkdir -p -- "$backup_dir"
backup_dir=$(cd -- "$backup_dir" && pwd -P)
if [[ "$backup_dir" == "/" ]]; then
  echo "CDCI_BACKUP_DIR cannot be the filesystem root." >&2
  exit 2
fi

temporary=$(mktemp "$backup_dir/.cdci-postgres-backup.XXXXXXXX")
trap 'rm -f -- "$temporary"' EXIT
chmod 600 "$temporary"

pg_dump --dbname="$database_url" --format=custom --no-owner --no-acl \
  | age --encrypt --recipient "$recipient" --output "$temporary"

if [[ ! -s "$temporary" ]]; then
  echo "The encrypted PostgreSQL backup is empty." >&2
  exit 1
fi

timestamp=$(date -u +%Y%m%dT%H%M%SZ)
backup_file="$backup_dir/cdci-postgres-$timestamp-$$.dump.age"
if [[ -e "$backup_file" ]]; then
  echo "A backup with this timestamp already exists." >&2
  exit 1
fi
mv -- "$temporary" "$backup_file"
find "$backup_dir" -maxdepth 1 -type f -name 'cdci-postgres-*.dump.age' \
  -mmin "+$((retention_days * 1440))" -delete

echo "Encrypted PostgreSQL backup created: $backup_file"
