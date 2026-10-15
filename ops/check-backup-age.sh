#!/usr/bin/env bash
set -Eeuo pipefail

backup_dir=${CDCI_BACKUP_DIR:-/var/backups/cdci/postgres}
max_age_hours=${CDCI_BACKUP_MAX_AGE_HOURS:-26}

if [[ ! "$max_age_hours" =~ ^[1-9][0-9]{0,3}$ ]] || (( max_age_hours > 8760 )); then
  echo "CDCI_BACKUP_MAX_AGE_HOURS must be between 1 and 8760." >&2
  exit 2
fi
command -v find >/dev/null 2>&1 || { echo "Required command not found: find" >&2; exit 2; }
command -v sort >/dev/null 2>&1 || { echo "Required command not found: sort" >&2; exit 2; }

if [[ ! -d "$backup_dir" ]]; then
  echo "Backup directory does not exist: $backup_dir" >&2
  exit 1
fi

latest_epoch=$(find "$backup_dir" -maxdepth 1 -type f -name 'cdci-postgres-*.dump.age' \
  -printf '%T@ %p\n' | sort -nr | sed -n '1s/ .*//p')
if [[ -z "$latest_epoch" ]]; then
  echo "No encrypted CDCI PostgreSQL backup was found." >&2
  exit 1
fi

latest_epoch=${latest_epoch%%.*}
age_seconds=$(( $(date -u +%s) - latest_epoch ))
if (( age_seconds > max_age_hours * 3600 )); then
  echo "Latest CDCI PostgreSQL backup is older than $max_age_hours hours." >&2
  exit 1
fi

echo "Latest CDCI PostgreSQL backup is within the $max_age_hours-hour freshness limit."
