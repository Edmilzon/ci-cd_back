#!/usr/bin/env python3
"""Ephemeral, resource-limited Docker worker for CDCI manual pipeline runs."""

from __future__ import annotations

import base64
import json
import os
import queue
import re
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

import yaml


TOKEN_PATTERN = re.compile(r"(?i)(ghu_[a-z0-9_]{20,}|github_pat_[a-z0-9_]{20,}|gh[pousr]_[a-z0-9]{20,})")
IMAGE_PATTERN = re.compile(r"(?:[a-z0-9.-]+(?::[0-9]{1,5})?/)?[a-z0-9][a-z0-9._-]*(?:/[a-z0-9][a-z0-9._-]*)*(?::[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})?(?:@sha256:[a-f0-9]{64})?")
MAX_LOG_CHARS = 1_000_000
LOG_CHUNK_CHARS = 3000
LEASE_HEARTBEAT_SECONDS = 25


def env_int(name: str, default: int, minimum: int, maximum: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except ValueError:
        value = default
    return max(minimum, min(value, maximum))


def redact(value: str, secret: str = "") -> str:
    if secret:
        value = value.replace(secret, "[credencial ocultada]")
    return TOKEN_PATTERN.sub("[credencial ocultada]", value)


class BackendClient:
    def __init__(self, base_url: str, token: str, timeout: int = 20):
        if not base_url.startswith(("http://", "https://")):
            raise ValueError("CDCI_RUNNER_API_URL debe ser una URL HTTP(S).")
        if not token:
            raise ValueError("CDCI_RUNNER_TOKEN es obligatorio para iniciar el worker.")
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout

    def post(self, path: str, payload: dict) -> tuple[int, object | None]:
        request = urllib.request.Request(
            self.base_url + path,
            data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
            headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json", "Accept": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                body = response.read()
                return response.status, json.loads(body) if body else None
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"La API del worker respondió HTTP {error.code}.") from None
        except (urllib.error.URLError, TimeoutError) as error:
            raise RuntimeError("No se pudo contactar la API del worker.") from error

    def claim(self, worker_id: str) -> dict | None:
        status, payload = self.post("/api/runner/claim", {"workerId": worker_id})
        if status == 204:
            return None
        if status != 200 or not isinstance(payload, dict):
            raise RuntimeError("La API devolvió una respuesta de claim no válida.")
        return payload

    def append_log(self, run_id: str, worker_id: str, stream: str, message: str) -> None:
        self.post(f"/api/runner/runs/{run_id}/logs", {"workerId": worker_id, "stream": stream, "message": message})

    def heartbeat(self, run_id: str, worker_id: str) -> None:
        self.post(f"/api/runner/runs/{run_id}/heartbeat", {"workerId": worker_id})

    def complete(self, run_id: str, worker_id: str, succeeded: bool, failure: str = "") -> None:
        payload = {"workerId": worker_id, "status": "SUCCEEDED" if succeeded else "FAILED", "failureMessage": failure or None}
        self.post(f"/api/runner/runs/{run_id}/complete", payload)


class LogSink:
    def __init__(self, client: BackendClient, run_id: str, worker_id: str, github_token: str):
        self.client = client
        self.run_id = run_id
        self.worker_id = worker_id
        self.github_token = github_token
        self.total_chars = 0
        self.truncated = False

    def write(self, stream: str, value: str) -> None:
        value = redact(value, self.github_token)
        for offset in range(0, len(value), LOG_CHUNK_CHARS):
            if self.total_chars >= MAX_LOG_CHARS:
                if not self.truncated:
                    self.truncated = True
                    self.client.append_log(self.run_id, self.worker_id, "SYSTEM", "Salida truncada al alcanzar el límite de logs de CDCI.")
                return
            chunk = value[offset : offset + min(LOG_CHUNK_CHARS, MAX_LOG_CHARS - self.total_chars)]
            if not chunk.strip():
                continue
            self.client.append_log(self.run_id, self.worker_id, stream, chunk)
            self.total_chars += len(chunk)


class LeaseKeeper:
    def __init__(self, client: BackendClient, run_id: str, worker_id: str):
        self.client = client
        self.run_id = run_id
        self.worker_id = worker_id
        self.stop_event = threading.Event()
        self.lost_event = threading.Event()
        self.thread = threading.Thread(target=self._run, name=f"lease-{run_id[:8]}", daemon=True)

    def start(self) -> None:
        self.thread.start()

    def stop(self) -> None:
        self.stop_event.set()
        self.thread.join(timeout=2)

    def _run(self) -> None:
        failures = 0
        while not self.stop_event.wait(LEASE_HEARTBEAT_SECONDS):
            try:
                self.client.heartbeat(self.run_id, self.worker_id)
                failures = 0
            except Exception:
                failures += 1
                if failures >= 2:
                    self.lost_event.set()
                    return


def safe_repository(claim: dict) -> tuple[str, str]:
    repository_name = claim.get("repositoryName", "")
    clone_url = claim.get("repositoryCloneUrl", "")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository_name):
        raise ValueError("El nombre del repositorio no es válido.")
    expected = f"https://github.com/{repository_name}.git"
    if clone_url != expected:
        raise ValueError("El worker solo permite clonar HTTPS desde github.com.")
    return repository_name, clone_url


def safe_branch(branch: str) -> str:
    if not isinstance(branch, str) or not re.fullmatch(r"[A-Za-z0-9._/-]{1,255}", branch):
        raise ValueError("La referencia del repositorio no es válida.")
    if branch.startswith(("-", "/")) or branch.endswith(("/", ".")) or ".." in branch or "//" in branch or "@{" in branch:
        raise ValueError("La referencia del repositorio no es válida.")
    if any(part.endswith(".lock") for part in branch.split("/")):
        raise ValueError("La referencia del repositorio no es válida.")
    return branch


def clone_repository(claim: dict, target: Path, sink: LogSink, lease_lost: threading.Event) -> None:
    repository_name, clone_url = safe_repository(claim)
    branch = safe_branch(claim.get("branchName", ""))
    token = claim.get("githubAccessToken", "")
    if not isinstance(token, str) or not token:
        raise ValueError("GitHub no devolvió una credencial de lectura para el repositorio.")
    sink.write("SYSTEM", f"Descargando {repository_name} · rama {branch}.\n")

    encoded = base64.b64encode(f"x-access-token:{token}".encode("utf-8")).decode("ascii")
    env = os.environ.copy()
    env.update({
        "GIT_TERMINAL_PROMPT": "0",
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_GLOBAL": "/dev/null",
        "GIT_CONFIG_COUNT": "1",
        "GIT_CONFIG_KEY_0": "http.https://github.com/.extraheader",
        "GIT_CONFIG_VALUE_0": f"Authorization: Basic {encoded}",
    })
    if lease_lost.is_set():
        raise RuntimeError("El worker perdió el lease de la ejecución.")
    result = subprocess.run(
        ["git", "-c", "credential.helper=", "-c", "core.hooksPath=/dev/null", "clone", "--quiet", "--depth", "1", "--no-recurse-submodules", "--branch", branch, clone_url, str(target)],
        env=env,
        capture_output=True,
        text=True,
        timeout=300,
        check=False,
    )
    if result.returncode != 0:
        details = redact((result.stderr or "").strip(), token)
        raise RuntimeError(details[:1800] or "No se pudo clonar el repositorio.")
    if lease_lost.is_set():
        raise RuntimeError("El worker perdió el lease de la ejecución.")
    sink.write("SYSTEM", "Repositorio preparado; la credencial de GitHub no se entrega a los contenedores de trabajo.\n")


def prepare_workspace_permissions(workspace: Path) -> None:
    """Let the unprivileged job user write build outputs without following repository symlinks."""
    for current, directories, files in os.walk(workspace, followlinks=False):
        os.chown(current, 65532, 65532, follow_symlinks=False)
        for entry in directories + files:
            os.chown(os.path.join(current, entry), 65532, 65532, follow_symlinks=False)


def container_args(image: str, network: str, workspace: Path, command: str, memory_mb: int, cpu_count: int, pids: int) -> list[str]:
    if not isinstance(image, str) or not IMAGE_PATTERN.fullmatch(image):
        raise ValueError("El trabajo usa una imagen de contenedor no válida.")
    if network not in {"none", "bridge"}:
        raise ValueError("La red del trabajo debe ser none o bridge.")
    return [
        "docker", "run", "--detach", "--pull=missing", "--label", "cdci.managed=true", "--network", network,
        "--memory", f"{memory_mb}m", "--cpus", str(cpu_count), "--pids-limit", str(pids),
        "--read-only", "--security-opt", "no-new-privileges", "--cap-drop", "ALL",
        "--user", "65532:65532", "--tmpfs", "/tmp:rw,nosuid,size=256m",
        "--ulimit", "nofile=1024:1024", "--volume", f"{workspace}:/workspace:rw",
        "--workdir", "/workspace", "--env", "CI=true", "--env", "HOME=/tmp",
        "--entrypoint", "/bin/sh", image, "-c", command,
    ]


def run_step(image: str, network: str, workspace: Path, command: str, sink: LogSink,
             lease_lost: threading.Event, timeout_seconds: int, memory_mb: int, cpu_count: int, pids: int) -> tuple[bool, str]:
    args = container_args(image, network, workspace, command, memory_mb, cpu_count, pids)
    try:
        started = subprocess.run(args, capture_output=True, text=True, timeout=300, check=False)
    except subprocess.TimeoutExpired:
        return False, "Se agotó el tiempo máximo para preparar la imagen del paso."
    if started.returncode != 0:
        message = redact((started.stderr or "").strip(), sink.github_token)
        sink.write("STDERR", message or "Docker no pudo iniciar el contenedor del paso.")
        return False, "No se pudo iniciar el contenedor del paso."

    container_id = started.stdout.strip()
    if not re.fullmatch(r"[a-fA-F0-9]{12,64}", container_id):
        return False, "Docker devolvió un identificador de contenedor no válido."

    logs = subprocess.Popen(["docker", "logs", "--follow", container_id], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    wait = subprocess.Popen(["docker", "wait", container_id], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    chunks: queue.Queue[bytes | None] = queue.Queue(maxsize=64)
    stop_pump = threading.Event()

    def pump() -> None:
        try:
            assert logs.stdout is not None
            while True:
                chunk = logs.stdout.read1(4096)
                if not chunk or stop_pump.is_set():
                    break
                while not stop_pump.is_set():
                    try:
                        chunks.put(chunk, timeout=0.2)
                        break
                    except queue.Full:
                        continue
        finally:
            try:
                chunks.put(None, timeout=0.2)
            except queue.Full:
                pass

    reader = threading.Thread(target=pump, name=f"logs-{container_id[:8]}", daemon=True)
    reader.start()
    deadline = time.monotonic() + timeout_seconds
    reason = ""
    saw_end = False
    exit_code: int | None = None
    try:
        while wait.poll() is None:
            if lease_lost.is_set():
                reason = "El worker perdió el lease; se detuvo el paso."
                break
            if time.monotonic() >= deadline:
                reason = f"El paso superó el límite de {timeout_seconds} segundos."
                break
            try:
                chunk = chunks.get(timeout=0.2)
            except queue.Empty:
                continue
            if chunk is None:
                saw_end = True
            else:
                sink.write("STDOUT", chunk.decode("utf-8", errors="replace"))

        if reason:
            subprocess.run(["docker", "kill", container_id], capture_output=True, timeout=15, check=False)
        try:
            output, error = wait.communicate(timeout=30)
        except subprocess.TimeoutExpired:
            wait.kill()
            output, error = wait.communicate()
            reason = reason or "Docker no confirmó la finalización del paso."
        if wait.returncode != 0:
            details = redact((error or "").strip(), sink.github_token)
            reason = reason or details or "Docker no pudo esperar la finalización del paso."
        elif not reason:
            try:
                exit_code = int((output or "").strip().splitlines()[-1])
            except (ValueError, IndexError):
                reason = "Docker no devolvió el código de salida del paso."

        if not saw_end:
            while True:
                try:
                    chunk = chunks.get_nowait()
                except queue.Empty:
                    break
                if chunk is None:
                    break
                sink.write("STDOUT", chunk.decode("utf-8", errors="replace"))
        reader.join(timeout=5)
        if reason:
            sink.write("STDERR", reason + "\n")
            return False, reason
        if exit_code != 0:
            failure = f"El comando terminó con código {exit_code}."
            sink.write("STDERR", failure + "\n")
            return False, failure
        return True, ""
    finally:
        stop_pump.set()
        if logs.poll() is None:
            logs.terminate()
            try:
                logs.wait(timeout=3)
            except subprocess.TimeoutExpired:
                logs.kill()
        if wait.poll() is None:
            wait.kill()
            wait.wait(timeout=3)
        subprocess.run(["docker", "rm", "--force", container_id], capture_output=True, timeout=15, check=False)


def run_pipeline(claim: dict, client: BackendClient, worker_id: str, lease_lost: threading.Event) -> tuple[bool, str]:
    run_id = str(claim.get("runId", ""))
    token = str(claim.get("githubAccessToken", ""))
    sink = LogSink(client, run_id, worker_id, token)
    timeout_seconds = env_int("CDCI_RUNNER_STEP_TIMEOUT_SECONDS", 1800, 30, 1800)
    run_timeout_seconds = env_int("CDCI_RUNNER_RUN_TIMEOUT_SECONDS", 21600, 60, 21600)
    memory_mb = env_int("CDCI_RUNNER_MEMORY_MB", 2048, 256, 8192)
    cpu_count = env_int("CDCI_RUNNER_CPU_COUNT", 1, 1, 4)
    pids = env_int("CDCI_RUNNER_PIDS_LIMIT", 256, 32, 1024)
    default_image = os.getenv("CDCI_RUNNER_DEFAULT_IMAGE", "node:22-bookworm-slim")
    if not IMAGE_PATTERN.fullmatch(default_image):
        default_image = "node:22-bookworm-slim"
    run_deadline = time.monotonic() + run_timeout_seconds

    try:
        with tempfile.TemporaryDirectory(prefix="run-", dir=os.getenv("CDCI_RUNNER_WORKDIR", "/work")) as temporary:
            root = Path(temporary).resolve()
            workspace = root / "source"
            clone_repository(claim, workspace, sink, lease_lost)
            prepare_workspace_permissions(workspace)
            if lease_lost.is_set():
                raise RuntimeError("El worker perdió el lease de la ejecución.")
            definition = yaml.safe_load(str(claim.get("yaml", "")))
            if not isinstance(definition, dict) or not isinstance(definition.get("jobs"), dict) or not definition["jobs"]:
                raise ValueError("La versión del pipeline no contiene trabajos válidos.")
            jobs = definition["jobs"]
            if len(jobs) > 20:
                raise ValueError("El pipeline supera el máximo de trabajos.")
            for job_id, job in jobs.items():
                if not isinstance(job, dict) or not isinstance(job.get("steps"), list) or not job["steps"]:
                    raise ValueError(f"El trabajo {job_id} no contiene pasos válidos.")
                image = job.get("image", default_image)
                network = job.get("network", "none")
                if not isinstance(image, str) or not IMAGE_PATTERN.fullmatch(image) or network not in {"none", "bridge"}:
                    raise ValueError(f"La configuración del trabajo {job_id} no es válida.")
                sink.write("SYSTEM", f"Iniciando trabajo {job_id} en {image} · red {network}.\n")
                for index, step in enumerate(job["steps"], start=1):
                    if lease_lost.is_set():
                        raise RuntimeError("El worker perdió el lease de la ejecución.")
                    if not isinstance(step, dict) or not isinstance(step.get("run"), str) or not step["run"].strip():
                        raise ValueError(f"El paso {index} del trabajo {job_id} no contiene un comando válido.")
                    name = str(step.get("name") or f"Paso {index}")[:100]
                    sink.write("SYSTEM", f"▶ {job_id} · {name}\n")
                    remaining = int(run_deadline - time.monotonic())
                    if remaining <= 0:
                        raise RuntimeError(f"La ejecución superó el límite total de {run_timeout_seconds} segundos.")
                    success, failure = run_step(image, network, workspace, step["run"], sink, lease_lost,
                                                min(timeout_seconds, remaining), memory_mb, cpu_count, pids)
                    if not success:
                        raise RuntimeError(f"{job_id} · {name}: {failure}")
                sink.write("SYSTEM", f"✓ Trabajo {job_id} completado.\n")
        return True, ""
    except Exception as error:
        failure = redact(str(error), token).strip() or "La ejecución no se pudo completar."
        sink.write("SYSTEM", f"Ejecución fallida: {failure}\n")
        return False, failure[:1000]


def process_claim(claim: dict, client: BackendClient, worker_id: str) -> None:
    run_id = str(claim.get("runId", ""))
    if not re.fullmatch(r"[0-9a-fA-F-]{36}", run_id):
        raise ValueError("La API devolvió un ID de ejecución no válido.")
    keeper = LeaseKeeper(client, run_id, worker_id)
    keeper.start()
    try:
        success, failure = run_pipeline(claim, client, worker_id, keeper.lost_event)
        client.complete(run_id, worker_id, success, failure)
    finally:
        keeper.stop()


def main() -> None:
    worker_id = os.getenv("CDCI_RUNNER_ID", f"runner-{socket.gethostname()}-{uuid.uuid4().hex[:8]}")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,100}", worker_id):
        raise SystemExit("CDCI_RUNNER_ID solo puede tener letras, números, punto, guion y guion bajo.")
    client = BackendClient(os.getenv("CDCI_RUNNER_API_URL", "http://host.docker.internal:8080"), os.getenv("CDCI_RUNNER_TOKEN", ""))
    poll_seconds = env_int("CDCI_RUNNER_POLL_SECONDS", 5, 2, 60)
    print(f"CDCI worker {worker_id} conectado a {client.base_url}.", flush=True)
    work_root = Path(os.getenv("CDCI_RUNNER_WORKDIR", "/work")).resolve()
    work_root.mkdir(parents=True, exist_ok=True)
    for stale in work_root.glob("run-*"):
        if stale.is_dir() and stale.resolve().parent == work_root:
            shutil.rmtree(stale, ignore_errors=True)
    while True:
        try:
            claim = client.claim(worker_id)
            if claim is None:
                time.sleep(poll_seconds)
                continue
            print(f"Ejecución {claim.get('runId')} reclamada.", flush=True)
            process_claim(claim, client, worker_id)
            print(f"Ejecución {claim.get('runId')} finalizada.", flush=True)
        except KeyboardInterrupt:
            return
        except Exception as error:
            print(f"Worker: {redact(str(error), client.token)}", flush=True)
            time.sleep(poll_seconds)


if __name__ == "__main__":
    main()
