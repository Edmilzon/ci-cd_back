#!/bin/sh
set -eu

dockerd --host=unix:///var/run/docker.sock --data-root=/var/lib/docker &
dockerd_pid=$!

stop_services() {
    if [ -n "${worker_pid:-}" ]; then
        kill -TERM "$worker_pid" 2>/dev/null || true
        wait "$worker_pid" 2>/dev/null || true
    fi
    kill -TERM "$dockerd_pid" 2>/dev/null || true
    wait "$dockerd_pid" 2>/dev/null || true
}
trap stop_services INT TERM EXIT

ready=false
for _ in $(seq 1 60); do
    if docker info >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 1
done
if [ "$ready" != true ]; then
    echo "Docker engine no inició dentro del tiempo permitido." >&2
    exit 1
fi

stale_containers=$(docker ps -aq --filter label=cdci.managed=true)
if [ -n "$stale_containers" ]; then
    # Este daemon es exclusivo de CDCI; solo se eliminan contenedores de jobs con la etiqueta administrada.
    echo "$stale_containers" | xargs -r docker rm --force >/dev/null
fi

python3 /opt/cdci-runner/runner.py &
worker_pid=$!
wait "$worker_pid"
