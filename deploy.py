#!/usr/bin/env python3
"""Deploy the latest Prodamus backend from GitHub into Docker.

Run from the cloned repository on the server:
    python3 deploy.py

No docker-compose/YAML is used. The script:
  1. validates the external production environment file;
  2. hard-resets the selected branch to origin;
  3. builds a new Docker image;
  4. preserves the previous container as a rollback candidate;
  5. starts the new container bound to localhost only;
  6. waits for /actuator/health;
  7. restores the previous container automatically if the deployment fails.

Deployment settings can be overridden with host environment variables:
    PRODAMUS_BRANCH=main
    PRODAMUS_CONTAINER_NAME=prodamus-backend
    PRODAMUS_IMAGE_NAME=prodamus-backend:latest
    PRODAMUS_HTTP_BIND=127.0.0.1
    PRODAMUS_HTTP_PORT=8082
    PRODAMUS_ENV_FILE=/etc/prodamus/prodamus.env
    PRODAMUS_MEMORY_LIMIT=512m
    PRODAMUS_MEMORY_SWAP_LIMIT=768m

Application secrets are never read from the repository. Docker loads them from
PRODAMUS_ENV_FILE at container start.
"""
from __future__ import annotations

import json
import os
import pathlib
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
BRANCH = os.getenv("PRODAMUS_BRANCH", "main")
CONTAINER = os.getenv("PRODAMUS_CONTAINER_NAME", "prodamus-backend")
PREVIOUS = CONTAINER + "-previous"
IMAGE = os.getenv("PRODAMUS_IMAGE_NAME", "prodamus-backend:latest")
HTTP_BIND = os.getenv("PRODAMUS_HTTP_BIND", "127.0.0.1")
HTTP_PORT = os.getenv("PRODAMUS_HTTP_PORT", "8082")
ENV_FILE = pathlib.Path(os.getenv("PRODAMUS_ENV_FILE", "/etc/prodamus/prodamus.env"))
MEMORY_LIMIT = os.getenv("PRODAMUS_MEMORY_LIMIT", "512m")
MEMORY_SWAP_LIMIT = os.getenv("PRODAMUS_MEMORY_SWAP_LIMIT", "768m")
HEALTH_URL = f"http://127.0.0.1:{HTTP_PORT}/actuator/health"

REQUIRED_ENV = {
    "PRODAMUS_DB_HOST",
    "PRODAMUS_DB_PORT",
    "PRODAMUS_DB_NAME",
    "PRODAMUS_DB_USER",
    "PRODAMUS_DB_PASSWORD",
    "PRODAMUS_MASTER_KEY",
    "PRODAMUS_ADMIN_LOGIN",
    "PRODAMUS_ADMIN_PASSWORD",
    "PRODAMUS_SESSION_SECURE",
}


def run(*args: str, check: bool = True, quiet: bool = False) -> subprocess.CompletedProcess[str]:
    print("+", " ".join(args), flush=True)
    return subprocess.run(
        args,
        cwd=ROOT,
        text=True,
        check=check,
        stdout=subprocess.DEVNULL if quiet else None,
        stderr=subprocess.DEVNULL if quiet else None,
    )


def exists_container(name: str) -> bool:
    return subprocess.run(
        ["docker", "container", "inspect", name],
        cwd=ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    ).returncode == 0


def env_keys(path: pathlib.Path) -> set[str]:
    keys: set[str] = set()
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _ = line.split("=", 1)
        key = key.strip()
        if key:
            keys.add(key)
    return keys


def validate_environment() -> bool:
    if not ENV_FILE.is_file():
        print(f"ERROR: production environment file not found: {ENV_FILE}", file=sys.stderr)
        return False

    missing = sorted(REQUIRED_ENV - env_keys(ENV_FILE))
    if missing:
        print(
            "ERROR: production environment file is missing: " + ", ".join(missing),
            file=sys.stderr,
        )
        return False

    permissions = stat.S_IMODE(ENV_FILE.stat().st_mode)
    if permissions & 0o077:
        print(
            f"ERROR: {ENV_FILE} permissions are {permissions:04o}; expected 0600 or stricter.",
            file=sys.stderr,
        )
        return False
    return True


def wait_until_healthy(timeout_seconds: int = 90) -> bool:
    deadline = time.monotonic() + timeout_seconds
    last_error = ""
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(HEALTH_URL, timeout=3) as response:
                payload = json.loads(response.read().decode("utf-8"))
                if response.status == 200 and payload.get("status") == "UP":
                    return True
                last_error = f"HTTP {response.status}: {payload}"
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError) as exc:
            last_error = str(exc)
        time.sleep(2)
    print(f"Health check failed: {last_error}", file=sys.stderr)
    return False


def start_container() -> None:
    command = [
        "docker",
        "run",
        "-d",
        "--name",
        CONTAINER,
        "--restart",
        "unless-stopped",
        "--env-file",
        str(ENV_FILE),
        "--memory",
        MEMORY_LIMIT,
        "--memory-swap",
        MEMORY_SWAP_LIMIT,
        "--log-opt",
        "max-size=20m",
        "--log-opt",
        "max-file=3",
        "-p",
        f"{HTTP_BIND}:{HTTP_PORT}:8080",
        "-v",
        "prodamus-logs:/app/logs",
        IMAGE,
    ]
    run(*command)


def main() -> int:
    if not (ROOT / ".git").exists():
        print("ERROR: deploy.py must be run from a cloned Git repository.", file=sys.stderr)
        return 2
    if not validate_environment():
        return 2

    run("git", "fetch", "origin", BRANCH)
    run("git", "checkout", BRANCH)
    run("git", "reset", "--hard", f"origin/{BRANCH}")
    run("docker", "build", "--pull", "-t", IMAGE, ".")

    if exists_container(PREVIOUS):
        run("docker", "rm", "-f", PREVIOUS)

    had_previous = exists_container(CONTAINER)
    if had_previous:
        run("docker", "stop", CONTAINER)
        run("docker", "rename", CONTAINER, PREVIOUS)

    try:
        start_container()
        print(f"Waiting for {HEALTH_URL} ...", flush=True)
        if not wait_until_healthy():
            raise RuntimeError("new container did not become healthy")
    except Exception as exc:
        print(f"\nDEPLOY FAILED: {exc}", file=sys.stderr)
        if exists_container(CONTAINER):
            run("docker", "rm", "-f", CONTAINER, check=False)
        if had_previous and exists_container(PREVIOUS):
            run("docker", "rename", PREVIOUS, CONTAINER, check=False)
            run("docker", "start", CONTAINER, check=False)
            print("Previous container restored.", file=sys.stderr)
        return 1

    if had_previous and exists_container(PREVIOUS):
        run("docker", "rm", PREVIOUS)

    print("\nContainer is healthy. Recent logs:\n")
    run("docker", "logs", "--tail", "80", CONTAINER, check=False)
    print(
        f"\nDone. Container: {CONTAINER}; binding: {HTTP_BIND}:{HTTP_PORT}; "
        f"memory: {MEMORY_LIMIT} (swap limit {MEMORY_SWAP_LIMIT})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
