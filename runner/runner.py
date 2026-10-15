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


def valid_secret_name(name: str) -> bool:
    return bool(re.fullmatch(r"[A-Z_][A-Z0-9_]{0,99}", name)) and name not in {
        "HOME", "PATH", "CI", "ENV", "IFS", "SHELLOPTS", "BASHOPTS",
    } and not name.startswith(("CDCI_", "DOCKER_", "GIT_", "LD_", "DYLD_", "BASH_", "PYTHON", "SSH_", "SUDO_"))


def redact(value: str, github_token: str = "", secrets: list[str] | tuple[str, ...] = ()) -> str:
    protected_values = [value for value in [github_token, *secrets] if value and len(value) >= 8]
    for secret in sorted(set(protected_values), key=len, reverse=True):
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

    def report_source(self, run_id: str, worker_id: str, commit_sha: str) -> None:
        self.post(f"/api/runner/runs/{run_id}/source", {"workerId": worker_id, "commitSha": commit_sha})

    def request_deployment(self, run_id: str, worker_id: str, job_id: str, environment: str, commit_sha: str) -> dict:
        _, result = self.post(f"/api/runner/runs/{run_id}/deployments", {
            "workerId": worker_id, "jobId": job_id, "environmentName": environment, "commitSha": commit_sha,
        })
        if not isinstance(result, dict) or not isinstance(result.get("id"), str):
            raise RuntimeError("La API devolvió una solicitud de despliegue no válida.")
        return result

    def deployment_approval_status(self, run_id: str, deployment_id: str, worker_id: str) -> dict:
        _, result = self.post(f"/api/runner/runs/{run_id}/deployments/{deployment_id}/approval-status", {"workerId": worker_id})
        if not isinstance(result, dict):
            raise RuntimeError("La API devolvió un estado de aprobación no válido.")
        return result

    def start_deployment(self, run_id: str, deployment_id: str, worker_id: str) -> dict:
        _, result = self.post(f"/api/runner/runs/{run_id}/deployments/{deployment_id}/start", {"workerId": worker_id})
        if not isinstance(result, dict) or result.get("status") not in {"IN_PROGRESS", "APPROVED"}:
            raise RuntimeError("No se pudo confirmar en GitHub el inicio del despliegue.")
        return result

    def complete_deployment(self, run_id: str, deployment_id: str, worker_id: str, succeeded: bool) -> None:
        self.post(f"/api/runner/runs/{run_id}/deployments/{deployment_id}/complete", {
            "workerId": worker_id, "succeeded": succeeded,
        })

    def complete(self, run_id: str, worker_id: str, succeeded: bool, failure: str = "") -> None:
        payload = {"workerId": worker_id, "status": "SUCCEEDED" if succeeded else "FAILED", "failureMessage": failure or None}
        self.post(f"/api/runner/runs/{run_id}/complete", payload)


class LogSink:
    def __init__(self, client: BackendClient, run_id: str, worker_id: str, github_token: str, secrets: dict[str, str]):
        self.client = client
        self.run_id = run_id
        self.worker_id = worker_id
        self.github_token = github_token
        self.secrets = list(secrets.values())
        self.pending: dict[str, str] = {}
        self.redaction_overlap = max([256, *(len(value) - 1 for value in self.secrets)])
        self.total_chars = 0
        self.truncated = False

    def write(self, stream: str, value: str, final: bool = False) -> None:
        if stream in {"STDOUT", "STDERR"}:
            buffered = self.pending.get(stream, "") + value
            if not final:
                safe_length = max(0, len(buffered) - self.redaction_overlap)
                self.pending[stream] = buffered[safe_length:]
                value = buffered[:safe_length]
            else:
                self.pending.pop(stream, None)
                value = buffered
        value = redact(value, self.github_token, self.secrets)
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

    def flush(self, stream: str) -> None:
        self.write(stream, "", final=True)


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


def clone_repository(claim: dict, target: Path, sink: LogSink, lease_lost: threading.Event) -> str:
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
    pinned_commit = claim.get("sourceCommitSha")
    if pinned_commit is not None and (not isinstance(pinned_commit, str) or not re.fullmatch(r"[a-f0-9]{40}", pinned_commit)):
        raise ValueError("El commit fijado para esta ejecución no es válido.")
    clone_command = ["git", "-c", "credential.helper=", "-c", "core.hooksPath=/dev/null", "clone", "--quiet", "--depth", "1", "--no-recurse-submodules"]
    if pinned_commit is None:
        clone_command.extend(["--branch", branch])
    clone_command.extend([clone_url, str(target)])
    result = subprocess.run(
        clone_command,
        env=env,
        capture_output=True,
        text=True,
        timeout=300,
        check=False,
    )
    if result.returncode != 0:
        details = redact((result.stderr or "").strip(), token)
        raise RuntimeError(details[:1800] or "No se pudo clonar el repositorio.")
    if pinned_commit is not None:
        fetched = subprocess.run(["git", "-C", str(target), "-c", "credential.helper=", "-c", "core.hooksPath=/dev/null",
                                  "fetch", "--quiet", "--depth", "1", "origin", pinned_commit],
                                 env=env, capture_output=True, text=True, timeout=300, check=False)
        if fetched.returncode != 0:
            details = redact((fetched.stderr or "").strip(), token)
            raise RuntimeError(details[:1800] or "No se pudo descargar el commit fijado para el despliegue.")
        checkout = subprocess.run(["git", "-C", str(target), "-c", "core.hooksPath=/dev/null", "checkout", "--quiet", "--detach", "FETCH_HEAD"],
                                  env=env, capture_output=True, text=True, timeout=60, check=False)
        if checkout.returncode != 0:
            details = redact((checkout.stderr or "").strip(), token)
            raise RuntimeError(details[:1800] or "No se pudo preparar el commit fijado para el despliegue.")
    if lease_lost.is_set():
        raise RuntimeError("El worker perdió el lease de la ejecución.")
    resolved = subprocess.run(["git", "-C", str(target), "rev-parse", "HEAD"], env=env, capture_output=True, text=True, timeout=15, check=False)
    if resolved.returncode != 0 or not re.fullmatch(r"[a-f0-9]{40}", resolved.stdout.strip()):
        raise RuntimeError("No se pudo confirmar el commit de origen del trabajo.")
    commit_sha = resolved.stdout.strip()
    if pinned_commit is not None and commit_sha != pinned_commit:
        raise RuntimeError("GitHub preparó un commit distinto al fijado para esta ejecución.")
    sink.write("SYSTEM", "Repositorio preparado; la credencial de GitHub no se entrega a los contenedores de trabajo.\n")
    return commit_sha


def prepare_workspace_permissions(workspace: Path) -> None:
    """Let the unprivileged job user write build outputs without following repository symlinks."""
    for current, directories, files in os.walk(workspace, followlinks=False):
        os.chown(current, 65532, 65532, follow_symlinks=False)
        for entry in directories + files:
            os.chown(os.path.join(current, entry), 65532, 65532, follow_symlinks=False)


def container_args(image: str, network: str, workspace: Path, command: str, memory_mb: int, cpu_count: int,
                  pids: int, secrets: dict[str, str], system_environment: dict[str, str]) -> list[str]:
    if not isinstance(image, str) or not IMAGE_PATTERN.fullmatch(image):
        raise ValueError("El trabajo usa una imagen de contenedor no válida.")
    if network not in {"none", "bridge"}:
        raise ValueError("La red del trabajo debe ser none o bridge.")
    container_environment = {**secrets, **system_environment}
    secret_env_args = [argument for name in sorted(container_environment) for argument in ("--env", name)]
    args = [
        "docker", "run", "--detach", "--pull=missing", "--label", "cdci.managed=true", "--network", network,
        "--memory", f"{memory_mb}m", "--cpus", str(cpu_count), "--pids-limit", str(pids),
        "--read-only", "--security-opt", "no-new-privileges", "--cap-drop", "ALL",
        "--user", "65532:65532", "--tmpfs", "/tmp:rw,nosuid,size=256m",
        "--ulimit", "nofile=1024:1024", "--volume", f"{workspace}:/workspace:rw",
        "--workdir", "/workspace", "--env", "CI=true", "--env", "HOME=/tmp",
        *secret_env_args, "--entrypoint", "/bin/sh", image, "-c", command,
    ]
    return args


def run_step(image: str, network: str, workspace: Path, command: str, sink: LogSink,
             lease_lost: threading.Event, timeout_seconds: int, memory_mb: int, cpu_count: int, pids: int,
             secrets: dict[str, str], system_environment: dict[str, str]) -> tuple[bool, str]:
    args = container_args(image, network, workspace, command, memory_mb, cpu_count, pids, secrets, system_environment)
    process_environment = os.environ.copy()
    process_environment.update(secrets)
    process_environment.update(system_environment)
    try:
        started = subprocess.run(args, capture_output=True, text=True, env=process_environment, timeout=300, check=False)
    except subprocess.TimeoutExpired:
        return False, "Se agotó el tiempo máximo para preparar la imagen del paso."
    if started.returncode != 0:
        message = redact((started.stderr or "").strip(), sink.github_token, sink.secrets)
        sink.write("STDERR", message or "Docker no pudo iniciar el contenedor del paso.", final=True)
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
            details = redact((error or "").strip(), sink.github_token, sink.secrets)
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
        sink.flush("STDOUT")
        if reason:
            sink.write("STDERR", reason + "\n", final=True)
            return False, reason
        if exit_code != 0:
            failure = f"El comando terminó con código {exit_code}."
            sink.write("STDERR", failure + "\n", final=True)
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
    secrets = claim.get("secrets", {})
    if not isinstance(secrets, dict) or any(not isinstance(name, str) or not valid_secret_name(name)
                                             or not isinstance(value, str) or not 8 <= len(value) <= 4096
                                             for name, value in secrets.items()):
        return False, "El worker recibió una configuración de secretos no válida."
    sink = LogSink(client, run_id, worker_id, token, secrets)
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
            commit_sha = clone_repository(claim, workspace, sink, lease_lost)
            client.report_source(run_id, worker_id, commit_sha)
            prepare_workspace_permissions(workspace)
            if lease_lost.is_set():
                raise RuntimeError("El worker perdió el lease de la ejecución.")
            definition = yaml.safe_load(str(claim.get("yaml", "")))
            if not isinstance(definition, dict) or not isinstance(definition.get("jobs"), dict) or not definition["jobs"]:
                raise ValueError("La versión del pipeline no contiene trabajos válidos.")
            jobs = definition["jobs"]
            if len(jobs) > 20:
                raise ValueError("El pipeline supera el máximo de trabajos.")
            rollback_environment = claim.get("rollbackEnvironment")
            rollback_deployed = False
            for job_id, job in jobs.items():
                if not isinstance(job, dict) or not isinstance(job.get("steps"), list) or not job["steps"]:
                    raise ValueError(f"El trabajo {job_id} no contiene pasos válidos.")
                deployment_environment = job.get("environment")
                if deployment_environment is not None and (not isinstance(deployment_environment, str)
                        or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", deployment_environment)):
                    raise ValueError(f"El entorno de despliegue del trabajo {job_id} no es válido.")
                if rollback_environment and deployment_environment and deployment_environment.lower() != rollback_environment.lower():
                    sink.write("SYSTEM", f"Se omite {job_id}: el rollback solo publica en {rollback_environment}.\n")
                    continue
                image = job.get("image", default_image)
                network = job.get("network", "none")
                if not isinstance(image, str) or not IMAGE_PATTERN.fullmatch(image) or network not in {"none", "bridge"}:
                    raise ValueError(f"La configuración del trabajo {job_id} no es válida.")
                requested_secrets = job.get("secrets", [])
                if not isinstance(requested_secrets, list) or any(not isinstance(name, str) or not valid_secret_name(name)
                                                                  or name not in secrets for name in requested_secrets):
                    raise ValueError(f"Falta configurar uno o más secretos del trabajo {job_id}.")
                job_secrets = {name: secrets[name] for name in requested_secrets}
                system_environment = {
                    "CDCI_COMMIT_SHA": commit_sha,
                    "CDCI_ENVIRONMENT": deployment_environment or "",
                    "CDCI_ROLLBACK": "true" if rollback_environment else "false",
                }
                deployment_id = None
                deployment_started = False
                if deployment_environment:
                    deployment = client.request_deployment(run_id, worker_id, str(job_id), deployment_environment, commit_sha)
                    deployment_id = str(deployment["id"])
                    if deployment.get("status") == "WAITING_APPROVAL":
                        sink.write("SYSTEM", f"El despliegue a {deployment_environment} espera aprobación de una persona mantenedora.\n")
                    while deployment.get("status") == "WAITING_APPROVAL":
                        if lease_lost.is_set():
                            raise RuntimeError("El worker perdió el lease mientras esperaba aprobación del despliegue.")
                        remaining = int(run_deadline - time.monotonic())
                        if remaining <= 0:
                            raise RuntimeError("Se agotó el tiempo de espera de aprobación del despliegue.")
                        time.sleep(min(10, remaining))
                        deployment = client.deployment_approval_status(run_id, deployment_id, worker_id)
                        if deployment.get("rejected"):
                            raise RuntimeError(f"El despliegue a {deployment_environment} fue rechazado.")
                    if deployment.get("rejected"):
                        raise RuntimeError(f"El despliegue a {deployment_environment} fue rechazado.")
                    if not deployment.get("approved"):
                        raise RuntimeError("El despliegue no recibió aprobación válida.")
                    serialization_wait_logged = False
                    while True:
                        if lease_lost.is_set():
                            raise RuntimeError("El worker perdió el lease mientras esperaba el turno de despliegue.")
                        remaining = int(run_deadline - time.monotonic())
                        if remaining <= 0:
                            raise RuntimeError(f"Se agotó el tiempo para iniciar el despliegue a {deployment_environment}.")
                        start_result = client.start_deployment(run_id, deployment_id, worker_id)
                        if start_result.get("status") == "IN_PROGRESS":
                            deployment_started = True
                            break
                        if not serialization_wait_logged:
                            sink.write("SYSTEM", f"El entorno {deployment_environment} ya tiene un despliegue activo; CDCI conserva el orden.\n")
                            serialization_wait_logged = True
                        time.sleep(min(10, remaining))
                    sink.write("SYSTEM", f"Aprobado: comienza el comando de despliegue a {deployment_environment}.\n")
                sink.write("SYSTEM", f"Iniciando trabajo {job_id} en {image} · red {network}.\n")
                try:
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
                                                    min(timeout_seconds, remaining), memory_mb, cpu_count, pids,
                                                    job_secrets, system_environment)
                        if not success:
                            raise RuntimeError(f"{job_id} · {name}: {failure}")
                except Exception:
                    if deployment_started and deployment_id:
                        try:
                            client.complete_deployment(run_id, deployment_id, worker_id, False)
                        except Exception:
                            pass
                    raise
                if deployment_started and deployment_id:
                    client.complete_deployment(run_id, deployment_id, worker_id, True)
                    rollback_deployed = rollback_deployed or bool(rollback_environment)
                sink.write("SYSTEM", f"✓ Trabajo {job_id} completado.\n")
            if rollback_environment and not rollback_deployed:
                raise RuntimeError(f"La definición no contiene un trabajo de despliegue para {rollback_environment}.")
        return True, ""
    except Exception as error:
        failure = redact(str(error), token, sink.secrets).strip() or "La ejecución no se pudo completar."
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
