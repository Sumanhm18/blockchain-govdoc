#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"
source scripts/docker-env.sh
umask 077
mkdir -p .runtime/logs
NODE_IMAGE="$(python3 -c 'import json; print(json.load(open("config/node-runtime.json"))["node"])')"
FABRIC_NODEENV="$(python3 -c 'import json; print(json.load(open("config/node-runtime.json"))["fabric_nodeenv"])')"
NPM_VERSION="$(python3 -c 'import json; print(json.load(open("config/node-runtime.json"))["npm"])')"
docker build --build-arg NPM_VERSION="$NPM_VERSION" --build-arg NODE_IMAGE="$NODE_IMAGE" --build-arg FABRIC_NODEENV="$FABRIC_NODEENV" \
  -t govdoc-fabric-nodeenv:build -f deploy/fabric-nodeenv.Dockerfile deploy > .runtime/logs/node-runtime-build.log 2>&1
IMAGE_ID="$(docker image inspect --format '{{.Id}}' govdoc-fabric-nodeenv:build)"
IMAGE_REF="govdoc-fabric-nodeenv:${IMAGE_ID#sha256:}"
docker tag "$IMAGE_ID" "$IMAGE_REF"
python3 - "$IMAGE_REF" "$IMAGE_ID" <<'PY'
import json,sys
from pathlib import Path
p=Path('.runtime/node-runtime.json')
t=p.with_suffix('.tmp')
t.write_text(json.dumps({'reference':sys.argv[1],'image_id':sys.argv[2]}))
t.replace(p)
PY
printf 'Built patched Fabric Node runtime: %s\n' "$IMAGE_ID"
