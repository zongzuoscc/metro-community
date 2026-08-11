#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
image_lock="$repo_root/deploy/stage-c-images.lock.json"
ollama_base_url=${OLLAMA_BASE_URL:-http://127.0.0.1:21434}
registry_url=https://registry.ollama.ai/v2/library/bge-m3/manifests/latest
manifest_file=$(mktemp "${TMPDIR:-/tmp}/metro-bge-m3-manifest.XXXXXX.json")
response_file=$(mktemp "${TMPDIR:-/tmp}/metro-bge-m3-response.XXXXXX.json")

cleanup() {
  python3 - "$manifest_file" "$response_file" <<'PY'
from pathlib import Path
import sys
for value in sys.argv[1:]:
    Path(value).unlink(missing_ok=True)
PY
}
trap cleanup EXIT INT TERM

readarray_values=$(python3 - "$image_lock" <<'PY'
import json
import sys
from pathlib import Path
model = json.loads(Path(sys.argv[1]).read_text())["models"]["bgeM3"]
print(model["upstreamReference"])
print(model["localAlias"])
print(model["manifestDigest"])
PY
)
upstream=$(printf '%s\n' "$readarray_values" | sed -n '1p')
local_alias=$(printf '%s\n' "$readarray_values" | sed -n '2p')
manifest_digest=$(printf '%s\n' "$readarray_values" | sed -n '3p')

curl -fsSL "$registry_url" -o "$manifest_file"
python3 - "$manifest_file" "$image_lock" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

manifest_bytes = Path(sys.argv[1]).read_bytes()
manifest = json.loads(manifest_bytes)
locked = json.loads(Path(sys.argv[2]).read_text())["models"]["bgeM3"]
actual_digest = "sha256:" + hashlib.sha256(manifest_bytes).hexdigest()
if actual_digest != locked["manifestDigest"]:
    raise SystemExit(f"BGE-M3 manifest drift: {actual_digest}")

actual_blobs = [manifest["config"], *manifest["layers"]]
expected_blobs = locked["blobs"]
if actual_blobs != expected_blobs:
    raise SystemExit("BGE-M3 blob manifest differs from the deployment lock")
PY

curl -fsS "$ollama_base_url/api/version" >/dev/null
pull_payload=$(python3 - "$upstream" <<'PY'
import json
import sys
print(json.dumps({"model": sys.argv[1], "stream": False}))
PY
)
curl -fsS -X POST -H 'Content-Type: application/json' \
  -d "$pull_payload" "$ollama_base_url/api/pull" -o "$response_file"
python3 - "$response_file" <<'PY'
import json
import sys
from pathlib import Path
response = json.loads(Path(sys.argv[1]).read_text())
if response.get("status") != "success":
    raise SystemExit("Ollama did not report a successful BGE-M3 pull")
PY

tags=$(curl -fsS "$ollama_base_url/api/tags")
python3 - "$upstream" "$manifest_digest" "$tags" <<'PY'
import json
import sys
name, expected, payload = sys.argv[1:]
models = json.loads(payload).get("models", [])
actual = next((entry.get("digest") for entry in models
               if entry.get("name") == name or entry.get("model") == name), None)
if actual != expected:
    raise SystemExit(f"local upstream model digest mismatch: {actual}")
PY

copy_payload=$(python3 - "$upstream" "$local_alias" <<'PY'
import json
import sys
print(json.dumps({"source": sys.argv[1], "destination": sys.argv[2]}))
PY
)
curl -fsS -X POST -H 'Content-Type: application/json' \
  -d "$copy_payload" "$ollama_base_url/api/copy" >/dev/null

tags=$(curl -fsS "$ollama_base_url/api/tags")
python3 - "$local_alias" "$manifest_digest" "$tags" <<'PY'
import json
import sys
name, expected, payload = sys.argv[1:]
models = json.loads(payload).get("models", [])
actual = next((entry.get("digest") for entry in models
               if entry.get("name") == name or entry.get("model") == name), None)
if actual != expected:
    raise SystemExit(f"immutable local BGE-M3 alias mismatch: {actual}")
PY

echo "Provisioned $local_alias at $manifest_digest"
