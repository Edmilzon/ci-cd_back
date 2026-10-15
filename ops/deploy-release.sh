#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: ops/deploy-release.sh <archive> <release-tag> <source-sha>" >&2
  exit 2
fi

archive="$1"
release_tag="$2"
source_sha="$3"
component="backend"
install_root="/srv/cdci/backend"
runtime_group="cdci-backend"
service="cdci-backend.service"
healthcheck_url="http://127.0.0.1:8080/api/health/ready"

if [[ ! "$release_tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo "Invalid release tag: expected vMAJOR.MINOR.PATCH." >&2
  exit 2
fi
if [[ ! "$source_sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Invalid source commit SHA." >&2
  exit 2
fi
if [[ ! -f "$archive" || ! -f "${archive}.sha256" ]]; then
  echo "The release archive and its SHA-256 file are required." >&2
  exit 2
fi
if ! (cd "$(dirname "$archive")" && sha256sum -c "$(basename "${archive}.sha256")"); then
  echo "The downloaded release archive failed its checksum." >&2
  exit 1
fi

for command_name in tar sha256sum curl sudo systemctl; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Required deployment command is missing: $command_name" >&2
    exit 1
  fi
done

archive_sha="$(sha256sum "$archive" | awk '{print $1}')"
run_id="${GITHUB_RUN_ID:-local-$$}"
if [[ ! "$run_id" =~ ^[A-Za-z0-9-]{1,64}$ ]]; then
  echo "Invalid workflow run id." >&2
  exit 2
fi
remote_archive="/tmp/cdci-${component}-release-${release_tag}-${run_id}.tar.gz"

ssh_directory="$(mktemp -d)"
cleanup_local() {
  rm -rf -- "$ssh_directory"
}
trap cleanup_local EXIT

if [[ -n "${CDCI_DEPLOY_SSH_KEY:-}" && -n "${CDCI_DEPLOY_KNOWN_HOSTS:-}" ]]; then
  deploy_host="${CDCI_DEPLOY_HOST:-}"
  deploy_user="${CDCI_DEPLOY_USER:-}"
  deploy_port="${CDCI_DEPLOY_PORT:-22}"
  if [[ ! "$deploy_host" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ || "$deploy_host" == *..* ]]; then
    echo "Set CDCI_DEPLOY_HOST to a DNS name or IPv4 address in the GitHub environment." >&2
    exit 2
  fi
  if [[ ! "$deploy_user" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]]; then
    echo "Set CDCI_DEPLOY_USER to the restricted deployment account in the GitHub environment." >&2
    exit 2
  fi
  if [[ ! "$deploy_port" =~ ^[0-9]{1,5}$ ]] || (( 10#$deploy_port < 1 || 10#$deploy_port > 65535 )); then
    echo "CDCI_DEPLOY_PORT must be between 1 and 65535." >&2
    exit 2
  fi
  deploy_key_file="$ssh_directory/id_ed25519"
  known_hosts_file="$ssh_directory/known_hosts"
  printf '%s\n' "$CDCI_DEPLOY_SSH_KEY" > "$deploy_key_file"
  printf '%s\n' "$CDCI_DEPLOY_KNOWN_HOSTS" > "$known_hosts_file"
  chmod 0600 "$deploy_key_file" "$known_hosts_file"
  known_hosts_target="$deploy_host"
  if (( 10#$deploy_port != 22 )); then
    known_hosts_target="[$deploy_host]:$deploy_port"
  fi
  if ! ssh-keygen -F "$known_hosts_target" -f "$known_hosts_file" >/dev/null; then
    echo "The pinned host key does not match CDCI_DEPLOY_HOST and CDCI_DEPLOY_PORT." >&2
    exit 2
  fi
  scp -i "$deploy_key_file" -P "$deploy_port" \
    -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes \
    -o UserKnownHostsFile="$known_hosts_file" -o ConnectTimeout=15 \
    "$archive" "${deploy_user}@${deploy_host}:${remote_archive}"

  ssh -i "$deploy_key_file" -p "$deploy_port" \
    -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes \
    -o UserKnownHostsFile="$known_hosts_file" -o ConnectTimeout=15 \
    "${deploy_user}@${deploy_host}" bash -s -- \
    "$release_tag" "$source_sha" "$archive_sha" "$run_id" "$install_root" "$service" "$healthcheck_url" "$remote_archive" <<'REMOTE_SCRIPT'
set -Eeuo pipefail
release_tag="$1"
source_sha="$2"
archive_sha="$3"
run_id="$4"
install_root="$5"
service="$6"
healthcheck_url="$7"
archive_path="$8"

[[ "$release_tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]
[[ "$source_sha" =~ ^[0-9a-f]{40}$ ]]
[[ "$archive_sha" =~ ^[0-9a-f]{64}$ ]]
[[ "$run_id" =~ ^[A-Za-z0-9-]{1,64}$ ]]
[[ "$install_root" == "/srv/cdci/backend" ]]
[[ "$service" == "cdci-backend.service" ]]
[[ "$healthcheck_url" == "http://127.0.0.1:8080/api/health/ready" ]]
[[ "$archive_path" == "/tmp/cdci-backend-release-${release_tag}-${run_id}.tar.gz" ]]
for command_name in tar sha256sum curl sudo systemctl; do
  command -v "$command_name" >/dev/null 2>&1 || { echo "Missing remote command: $command_name" >&2; exit 1; }
done
trap 'rm -f -- "$archive_path"' EXIT
actual_sha="$(sha256sum "$archive_path" | awk '{print $1}')"
[[ "$actual_sha" == "$archive_sha" ]] || { echo "Transferred archive checksum mismatch." >&2; exit 1; }

releases_dir="$install_root/releases"
release_dir="$releases_dir/$release_tag"
mkdir -p "$releases_dir"
if [[ -e "$install_root/current" && ! -L "$install_root/current" ]]; then
  echo "$install_root/current must be a symlink managed by the release workflow." >&2
  exit 1
fi
previous_link=""
if [[ -L "$install_root/current" ]]; then
  previous_link="$(readlink -- "$install_root/current")"
  if [[ ! "$previous_link" =~ ^releases/v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ || ! -d "$install_root/$previous_link" ]]; then
    echo "The current release symlink points outside a valid release directory." >&2
    exit 1
  fi
fi

new_release=0
if [[ -e "$release_dir" ]]; then
  if [[ ! -d "$release_dir" || -L "$release_dir" || ! -f "$release_dir/.package.sha256" ]]; then
    echo "The release path exists but is not a managed release: $release_tag" >&2
    exit 1
  fi
  stored_sha="$(cat "$release_dir/.package.sha256")"
  stored_source_sha="$(cat "$release_dir/SOURCE_SHA" 2>/dev/null || true)"
  stored_release_tag="$(cat "$release_dir/RELEASE_TAG" 2>/dev/null || true)"
  if [[ ! "$stored_sha" =~ ^[0-9a-f]{64}$ || "$stored_source_sha" != "$source_sha" || "$stored_release_tag" != "$release_tag" ]]; then
    echo "Refusing to reuse release $release_tag because its stored source does not match." >&2
    exit 1
  fi
  if [[ "$stored_sha" != "$archive_sha" ]]; then
    echo "Reusing the previously deployed immutable package for $release_tag." >&2
  fi
else
stage_dir=""
cleanup_remote() {
  rm -f -- "$archive_path"
  if [[ -n "$stage_dir" && -d "$stage_dir" ]]; then rm -rf -- "$stage_dir"; fi
}
trap cleanup_remote EXIT
stage_dir="$(mktemp -d "$releases_dir/.deploy-${run_id}.XXXXXX")"
  if ! tar -tzf "$archive_path" | awk '/^\// || /(^|\/)\.\.(\/|$)/ { bad=1 } END { exit bad }'; then
    echo "The release archive contains an unsafe path." >&2
    rm -rf -- "$stage_dir"
    exit 1
  fi
  tar --no-same-owner --no-same-permissions -xzf "$archive_path" -C "$stage_dir"
  if [[ "$(cat "$stage_dir/RELEASE_TAG" 2>/dev/null || true)" != "$release_tag" || "$(cat "$stage_dir/SOURCE_SHA" 2>/dev/null || true)" != "$source_sha" || ! -s "$stage_dir/app.jar" ]]; then
    echo "Release metadata or backend JAR does not match the selected source." >&2
    rm -rf -- "$stage_dir"
    exit 1
  fi
  chgrp -R "$runtime_group" "$stage_dir"
  chmod -R g+rX "$stage_dir"
  chmod 2750 "$stage_dir"
  printf '%s\n' "$archive_sha" > "$stage_dir/.package.sha256"
  mv -- "$stage_dir" "$release_dir"
  stage_dir=""
  new_release=1
fi

switch_current() {
  local target="$1"
  local next_link="$install_root/.current-${run_id}-${RANDOM}"
  ln -s -- "$target" "$next_link"
  mv -Tf -- "$next_link" "$install_root/current"
}

rollback() {
  echo "Readiness failed; restoring the previous backend release." >&2
  if [[ -n "$previous_link" ]]; then
    switch_current "$previous_link"
    sudo -n systemctl restart "$service" || true
  else
    rm -f -- "$install_root/current"
    sudo -n systemctl stop "$service" || true
  fi
  if (( new_release == 1 )); then
    rm -rf -- "$release_dir"
  fi
}

switch_current "releases/$release_tag"
if ! sudo -n systemctl restart "$service"; then
  rollback
  exit 1
fi
healthy=0
for attempt in $(seq 1 30); do
  if curl --fail --silent --show-error --max-time 5 "$healthcheck_url" >/dev/null; then
    healthy=1
    break
  fi
  sleep 2
done
if (( healthy != 1 )); then
  rollback
  exit 1
fi

echo "Backend release $release_tag ($source_sha) is healthy in $install_root/current."
current_path="$(readlink -f -- "$install_root/current")"
previous_path=""
if [[ -n "$previous_link" ]]; then previous_path="$(readlink -f -- "$install_root/$previous_link")"; fi
mapfile -t old_releases < <(find "$releases_dir" -mindepth 1 -maxdepth 1 -type d -name 'v*' -printf '%T@ %p\n' | sort -rn | awk 'NR > 10 { $1=""; sub(/^ /, ""); print }')
for candidate in "${old_releases[@]}"; do
  if [[ "$candidate" != "$current_path" && "$candidate" != "$previous_path" && "$candidate" == "$releases_dir"/v* ]]; then
    rm -rf -- "$candidate"
  fi
done
REMOTE_SCRIPT
else
  echo "Set the GitHub environment SSH key and pinned host key before deploying." >&2
  exit 2
fi
