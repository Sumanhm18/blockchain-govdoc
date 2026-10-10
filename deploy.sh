#!/usr/bin/env bash
# Development deployment only. Production requirements are documented in docs/OPERATIONS.md.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"
umask 077
source scripts/docker-env.sh
./scripts/bootstrap.sh
./scripts/network.sh up
./scripts/network.sh deployCC
if [[ ! -f .runtime/identity/signcerts/cert.pem ]]; then python3 scripts/enroll-service.py; fi
mkdir -p .runtime/data .runtime/logs/api
export REGISTRY_UID="$(id -u)" REGISTRY_GID="$(id -g)"
docker compose up --build -d
python3 scripts/wait-ready.py http://127.0.0.1:18081/actuator/health/readiness
printf 'Development API ready at http://127.0.0.1:18081\n'
