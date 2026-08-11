#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
env_file=${ENV_FILE:-"$repo_root/.env"}
project=${COMPOSE_PROJECT_NAME:-metro-community-ai}
image_lock="$repo_root/deploy/stage-c-images.lock.json"
mutex_dir="${TMPDIR:-/tmp}/${project}.stage-c-ai.lock"

if ! mkdir "$mutex_dir" 2>/dev/null; then
  echo "Stage C AI deployment is already running for project $project" >&2
  exit 1
fi

rendered=$(mktemp "${TMPDIR:-/tmp}/${project}.compose.XXXXXX.json")
cleanup() {
  python3 - "$rendered" <<'PY'
from pathlib import Path
import sys
Path(sys.argv[1]).unlink(missing_ok=True)
PY
  rmdir "$mutex_dir" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

if [[ ! -f "$env_file" ]]; then
  echo "Missing Compose environment file: $env_file" >&2
  exit 1
fi

cd "$repo_root"
docker compose --project-name "$project" --env-file "$env_file" \
  --profile ai config --format json >"$rendered"

python3 - "$rendered" "$image_lock" <<'PY'
import json
import socket
import sys
from pathlib import Path

compose = json.loads(Path(sys.argv[1]).read_text())
lock = json.loads(Path(sys.argv[2]).read_text())
ai_services = ("etcd", "minio", "milvus", "ollama")
seen_ports = set()

for name in ai_services:
    actual = compose["services"][name]["image"]
    expected = lock["images"][name]["reference"]
    if actual != expected:
        raise SystemExit(f"{name} image differs from deployment lock")

for name in ai_services:
    service = compose["services"][name]
    for mapping in service.get("ports", []):
        host_ip = mapping.get("host_ip", "")
        if host_ip != "127.0.0.1":
            raise SystemExit(f"{name} publishes a non-loopback port: {host_ip}")
        published = int(mapping["published"])
        if published in seen_ports:
            raise SystemExit(f"duplicate host port: {published}")
        seen_ports.add(published)
        probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            probe.bind(("127.0.0.1", published))
        except OSError as error:
            raise SystemExit(f"host port {published} is unavailable: {error}") from error
        finally:
            probe.close()
PY

while IFS= read -r image; do
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    echo "Locked image is not present locally: $image" >&2
    echo "Provision the exact digest before running this launcher; automatic pulls are disabled." >&2
    exit 1
  fi
done < <(python3 - "$image_lock" <<'PY'
import json
import sys
from pathlib import Path
lock = json.loads(Path(sys.argv[1]).read_text())
for name in ("etcd", "minio", "milvus", "ollama"):
    print(lock["images"][name]["reference"])
PY
)

if [[ ${CHECK_ONLY:-false} == "true" ]]; then
  echo "Stage C AI Compose preflight passed for $project"
  exit 0
fi

docker compose --project-name "$project" --env-file "$env_file" \
  --profile ai up -d --pull never --wait etcd minio milvus ollama
