#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"
source scripts/docker-env.sh
export REGISTRY_UID="$(id -u)" REGISTRY_GID="$(id -g)"
export KEYCLOAK_IMAGE="$(python3 -c 'import json; print(json.load(open("config/staging-images.json"))["keycloak"])')"
export POSTGRES_IMAGE="$(python3 -c 'import json; print(json.load(open("config/staging-images.json"))["postgres"])')"
case "${1:-}" in
  up) python3 scripts/setup-staging.py; docker compose -f deploy/staging.compose.yml up --build --force-recreate -d ;;
  stop) docker compose -f deploy/staging.compose.yml stop ;;
  *) echo 'Usage: scripts/staging.sh {up|stop}'; exit 1 ;;
esac
