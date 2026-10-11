#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"
source scripts/docker-env.sh
export REGISTRY_UID="$(id -u)" REGISTRY_GID="$(id -g)"
export KEYCLOAK_IMAGE="$(python3 -c 'import json; print(json.load(open("config/staging-images.json"))["keycloak"])')"
export KEYCLOAK_RUNTIME_IMAGE="$(python3 -c 'import json; print(json.load(open("config/staging-images.json"))["keycloak_runtime"])')"
export POSTGRES_BASE_IMAGE="$(python3 -c 'import json; print(json.load(open("config/staging-images.json"))["postgres"])')"
export POSTGRES_IMAGE="${POSTGRES_IMAGE:-govdoc-postgres:local}"
export POSTGRES_VOLUME="${POSTGRES_VOLUME:-govdoc-staging_keycloak-db-alpine-v1}"
if [[ "${1:-}" == up && "$POSTGRES_VOLUME" == govdoc-staging_keycloak-db-alpine-v1 && -f .runtime/staging/postgres-migration.json ]]; then
  python3 -c 'import json; s=json.load(open(".runtime/staging/postgres-migration.json"))["status"]; assert s in ("restored", "verified"), "Migration was rolled back; review before switching databases"'
fi
if [[ "${1:-}" == up && "$POSTGRES_VOLUME" == govdoc-staging_keycloak-db-alpine-v1 && ! -f .runtime/staging/postgres-migration.json ]] && docker volume inspect govdoc-staging_keycloak-db >/dev/null 2>&1; then
  echo 'Existing identity database requires scripts/migrate-staging-postgres.py before switching images.' >&2
  exit 1
fi
case "${1:-}" in
  up)
    if [[ "$POSTGRES_IMAGE" == govdoc-postgres:local ]]; then
      docker build -t govdoc-postgres:local --build-arg "POSTGRES_IMAGE=$POSTGRES_BASE_IMAGE" -f deploy/postgres.Dockerfile deploy
    fi
    python3 scripts/setup-staging.py; docker compose -f deploy/staging.compose.yml up --build --force-recreate -d ;;
  stop) docker compose -f deploy/staging.compose.yml stop ;;
  *) echo 'Usage: scripts/staging.sh {up|stop}'; exit 1 ;;
esac
