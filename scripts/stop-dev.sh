#!/usr/bin/env bash
# Stop only this project's development containers; preserve identities, journal and volumes.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"
source scripts/docker-env.sh
docker compose stop
if [[ -d .runtime/fabric/test-network ]]; then
  cd .runtime/fabric/test-network
  export DOCKER_SOCK=/var/run/docker.sock COMPOSE_PROJECT_NAME=govdoc-dev-fabric
  docker compose -f compose/compose-test-net.yaml -f compose/docker/docker-compose-test-net.yaml -f compose/compose-ca.yaml -f compose/docker/docker-compose-ca.yaml stop
fi
